package equ.srv.actions;

import com.google.common.base.Throwables;
import equ.api.*;
import equ.api.cashregister.CashRegisterItemInfo;
import equ.api.cashregister.TransactionCashRegisterInfo;
import equ.api.scales.ScalesItemInfo;
import equ.api.scales.TransactionScalesInfo;
import equ.api.terminal.TransactionTerminalInfo;
import lsfusion.base.Pair;
import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.erp.integration.DefaultIntegrationActionProperty;
import lsfusion.interop.Compare;
import lsfusion.interop.remote.RMIUtils;
import lsfusion.server.classes.ConcreteCustomClass;
import lsfusion.server.classes.CustomClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.integration.*;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.linear.LCP;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.util.*;

public class TransactionExchangeActionProperty extends DefaultIntegrationActionProperty {
    
    ScriptingLogicsModule itemFashionLM;
    ScriptingLogicsModule machineryPriceTransactionStockTaxLM;
    ScriptingLogicsModule zReportDiscountCardLM;
    
    public TransactionExchangeActionProperty(ScriptingLogicsModule LM) throws ScriptingErrorLog.SemanticErrorException {
        super(LM);
    }

    @Override
    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {

        try {
            
            itemFashionLM = context.getBL().getModule("ItemFashion");
            machineryPriceTransactionStockTaxLM = context.getBL().getModule("MachineryPriceTransactionStockTax");
            zReportDiscountCardLM = context.getBL().getModule("ZReportDiscountCard");
            
            String sidEquipmentServer = (String) findProperty("sidEquipmentServerTransactionExchange").read(context);
            String serverHost = (String) findProperty("hostTransactionExchange").read(context);
            Integer connectPort = (Integer) findProperty("portTransactionExchange").read(context);
            String serverDB = (String) findProperty("serverDBTransactionExchange").read(context);
            EquipmentServerInterface remote = RMIUtils.rmiLookup(serverHost, connectPort, serverDB, "EquipmentServer");
            
            readTransactionInfo(context, remote, sidEquipmentServer);

            sendReceiptInfo(context, remote, sidEquipmentServer);
            
        } catch (RemoteException e) {
            throw Throwables.propagate(e);
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw Throwables.propagate(e);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        } catch (NotBoundException e) {
            throw Throwables.propagate(e);
        }
    }
    
    private void readTransactionInfo(ExecutionContext context, EquipmentServerInterface remote, String sidEquipmentServer) 
            throws RemoteException, SQLException, ScriptingErrorLog.SemanticErrorException, SQLHandledException {
        List<TransactionInfo> transactionList = remote.readTransactionInfo(sidEquipmentServer);

        List<List<List<Object>>> transactionData = getTransactionData(transactionList);
        importItemGroups(context, transactionData.get(0));
        importParentGroups(context, transactionData.get(1));
        importCashRegisterTransactionList(context, transactionData.get(2));
        importScalesTransactionList(context,  transactionData.get(3));
        importTerminalTransactionList(context, transactionData.get(4));
        importPriceCheckerTransactionList(context, transactionData.get(5));
    }

    private void sendReceiptInfo(ExecutionContext context, EquipmentServerInterface remote, String sidEquipmentServer)
            throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException, IOException {

        List<SalesInfo> salesInfoList = readSalesInfo(context);

        String result = remote.sendSalesInfo(salesInfoList, sidEquipmentServer, null);
        if (result == null) {
            List<String> succeededReceiptList = new ArrayList<String>();
            for (SalesInfo sale : salesInfoList) {
                succeededReceiptList.add(sale.getIdReceipt());
            }
            if (!succeededReceiptList.isEmpty())
                finishSendSalesInfo(context, succeededReceiptList);
        }
    }
    
    private List<SalesInfo> readSalesInfo(ExecutionContext context) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        Map<Object, List<Pair<String, BigDecimal>>> paymentMap = readPaymentMap(context);

        KeyExpr receiptExpr = new KeyExpr("receipt");
        KeyExpr receiptDetailExpr = new KeyExpr("receiptDetail");
        ImRevMap<Object, KeyExpr> receiptKeys = MapFact.toRevMap((Object) "receipt", receiptExpr, "receiptDetail", receiptDetailExpr);

        QueryBuilder<Object, Object> receiptQuery = new QueryBuilder<Object, Object>(receiptKeys);

        String[] receiptNames = new String[]{"numberZReportReceipt", "numberReceipt", "dateReceipt", "timeReceipt", "discountSumReceipt", "idEmployeeReceipt",
                "firstNameEmployeeReceipt", "lastNameEmployeeReceipt", "numberGroupCashRegisterReceipt", "numberCashRegisterReceipt"};
        LCP<?>[] receiptProperties = findProperties("numberZReportReceipt", "numberReceipt", "dateReceipt", "timeReceipt", "discountSumReceipt", "idEmployeeReceipt",
                "firstNameEmployeeReceipt", "lastNameEmployeeReceipt", "numberGroupCashRegisterReceipt", "numberCashRegisterReceipt");
        for (int j = 0; j < receiptProperties.length; j++) {
            receiptQuery.addProperty(receiptNames[j], receiptProperties[j].getExpr(receiptExpr));
        }
        if(zReportDiscountCardLM != null) {
            receiptQuery.addProperty("seriesNumberDiscountCardReceipt", zReportDiscountCardLM.findProperty("seriesNumberDiscountCardReceipt").getExpr(receiptExpr));
        }

        String[] receiptDetailNames = new String[]{"idBarcodeReceiptDetail", "quantityReceiptDetail", "priceReceiptDetail", "sumReceiptDetail", 
                "discountSumReceiptDetail", "typeReceiptDetail", "numberReceiptDetail", "idSkuReceiptDetail"};
        LCP<?>[] receiptDetailProperties = findProperties("idBarcodeReceiptDetail", "quantityReceiptDetail", "priceReceiptDetail", "sumReceiptDetail", 
                "discountSumReceiptDetail", "typeReceiptDetail", "numberReceiptDetail", "idSkuReceiptDetail");
        for (int j = 0; j < receiptDetailProperties.length; j++) {
            receiptQuery.addProperty(receiptDetailNames[j], receiptDetailProperties[j].getExpr(receiptDetailExpr));
        }

        receiptQuery.and(findProperty("notExportedIncrementReceipt").getExpr(receiptExpr).getWhere());
        receiptQuery.and(findProperty("receiptReceiptDetail").getExpr(receiptDetailExpr).compare(receiptExpr, Compare.EQUALS));

        ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> receiptResult = receiptQuery.executeClasses(context);

        List<SalesInfo> salesInfoList = new ArrayList<SalesInfo>();
        for (int i = 0, size = receiptResult.size(); i < size; i++) {
            Object receiptObject = receiptResult.getKey(i).get("receipt").object;
            ImMap<Object, ObjectValue> entry = receiptResult.getValue(i);

            BigDecimal sumCard = null;
            BigDecimal sumCash = null;
            BigDecimal sumGiftCard = null;
            List<Pair<String, BigDecimal>> paymentList = paymentMap.get(receiptObject);
            if(paymentList != null) {
                for (Pair<String, BigDecimal> payment : paymentList) {
                    String sidPayment = payment.first;
                    BigDecimal sumPayment = payment.second;
                    if (sidPayment.equals("card"))
                        sumCard = safeAdd(sumCard, sumPayment);
                    else if (sidPayment.equals("cash"))
                        sumCash = safeAdd(sumCash, sumPayment);
                    else if (sidPayment.equals("giftcard"))
                        sumGiftCard = safeAdd(sumGiftCard, sumPayment);
                    else sumCash = safeAdd(sumCash, sumPayment);
                }
            }

            String numberZReport = trim((String) entry.get("numberZReportReceipt").getValue());
            Integer numberReceipt = (Integer) entry.get("numberReceipt").getValue();
            Date dateReceipt = (Date) entry.get("dateReceipt").getValue();
            Time timeReceipt = (Time) entry.get("timeReceipt").getValue();
            BigDecimal discountSumReceipt = (BigDecimal) entry.get("discountSumReceipt").getValue();
            String idEmployee = trim((String) entry.get("idEmployeeReceipt").getValue());
            String firstNameContact = trim((String) entry.get("firstNameEmployeeReceipt").getValue());
            String lastNameContact = trim((String) entry.get("lastNameEmployeeReceipt").getValue());
            Integer nppGroupMachinery = (Integer) entry.get("numberGroupCashRegisterReceipt").getValue();
            Integer nppMachinery = (Integer) entry.get("numberCashRegisterReceipt").getValue();
            String seriesNumberDiscountCard = zReportDiscountCardLM == null ? null : trim((String) entry.get("seriesNumberDiscountCardReceipt").getValue());

            String barcodeReceiptDetail = trim((String) entry.get("idBarcodeReceiptDetail").getValue());
            BigDecimal quantityReceiptDetail = (BigDecimal) entry.get("quantityReceiptDetail").getValue();
            BigDecimal priceReceiptDetail = (BigDecimal) entry.get("priceReceiptDetail").getValue();
            BigDecimal sumReceiptDetail = (BigDecimal) entry.get("sumReceiptDetail").getValue();
            BigDecimal discountSumReceiptDetail = (BigDecimal) entry.get("discountSumReceiptDetail").getValue();
            Integer numberReceiptDetail = (Integer) entry.get("numberReceiptDetail").getValue();
            String typeReceiptDetail = trim((String) entry.get("typeReceiptDetail").getValue());
            boolean isGiftCard = typeReceiptDetail != null && typeReceiptDetail.equals("Сертификат");

            salesInfoList.add(new SalesInfo(isGiftCard, nppGroupMachinery, nppMachinery, numberZReport, numberReceipt,
                    dateReceipt, timeReceipt, idEmployee, firstNameContact, lastNameContact, sumCard, sumCash, sumGiftCard,
                    barcodeReceiptDetail, null, quantityReceiptDetail, priceReceiptDetail, sumReceiptDetail, discountSumReceiptDetail,
                    discountSumReceipt, seriesNumberDiscountCard, numberReceiptDetail, null));
        }
        return salesInfoList;
    }
    
    private Map<Object, List<Pair<String, BigDecimal>>> readPaymentMap(ExecutionContext context) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        KeyExpr paymentExpr = new KeyExpr("payment");
        KeyExpr receiptExpr = new KeyExpr("receipt");
        ImRevMap<Object, KeyExpr> paymentKeys = MapFact.toRevMap((Object) "payment", paymentExpr, "receipt", receiptExpr);

        QueryBuilder<Object, Object> paymentQuery = new QueryBuilder<Object, Object>(paymentKeys);

        String[] paymentNames = new String[]{"sidPaymentTypePayment", "sumPayment"};
        LCP<?>[] paymentProperties = findProperties("sidPaymentTypePayment", "sumPayment");
        for (int j = 0; j < paymentProperties.length; j++) {
            paymentQuery.addProperty(paymentNames[j], paymentProperties[j].getExpr(paymentExpr));
        }
        paymentQuery.and(findProperty("sumPayment").getExpr(paymentExpr).getWhere());
        paymentQuery.and(findProperty("receiptPayment").getExpr(paymentExpr).compare(receiptExpr, Compare.EQUALS));

        ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> paymentResult = paymentQuery.executeClasses(context);

        Map<Object, List<Pair<String, BigDecimal>>> paymentMap = new HashMap<Object, List<Pair<String, BigDecimal>>>();
        for (int i = 0, size = paymentResult.size(); i < size; i++) {
            Object receiptObject = paymentResult.getKey(i).get("receipt").object;
            ImMap<Object, ObjectValue> entryValue = paymentResult.getValue(i);
            String sidPayment = trim((String) entryValue.get("sidPaymentTypePayment").getValue());
            BigDecimal sumPayment = (BigDecimal) entryValue.get("sumPayment").getValue();
            
            List<Pair<String, BigDecimal>> paymentEntry = paymentMap.containsKey(receiptObject) ? paymentMap.get(receiptObject) : new ArrayList<Pair<String, BigDecimal>>();
            paymentEntry.add(Pair.create(sidPayment, sumPayment));
            paymentMap.put(receiptObject, paymentEntry);
        }
        return paymentMap;
    }

    public void finishSendSalesInfo(ExecutionContext context, List<String> succeededReceiptList) throws IOException, SQLException {
        try {
            DataSession session = context.createSession();
            for (String idReceipt : succeededReceiptList) {
                ObjectValue receiptObject = findProperty("receiptId").readClasses(session, new DataObject(idReceipt));
                if (receiptObject instanceof DataObject)
                    findProperty("exportedIncrementReceipt").change(true, session, (DataObject) receiptObject);
            }
            session.apply(context.getBL());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }
    
    private List<List<List<Object>>> getTransactionData(List<TransactionInfo> transactionList) {
        List<List<Object>> itemGroupData = new ArrayList<List<Object>>();
        List<List<Object>> parentGroupData = new ArrayList<List<Object>>();
        List<List<Object>> cashRegisterData = new ArrayList<List<Object>>();
        List<List<Object>> scalesData = new ArrayList<List<Object>>();
        List<List<Object>> terminalData = new ArrayList<List<Object>>();
        List<List<Object>> priceCheckerData = new ArrayList<List<Object>>();

        for(TransactionInfo transaction : transactionList) {

            String idTransaction = String.valueOf(transaction.id);
            Date dateTransaction = transaction.date == null ? null : new Date(transaction.date.getTime());
            Time timeTransaction = dateTransaction == null ? null : new Time(dateTransaction.getTime());
            List<ItemInfo> itemsListTransaction = transaction.itemsList;
            Boolean snapshotTransaction = format(transaction.snapshot);

            if(transaction.itemGroupMap != null) {
                for (Object itemGroupListEntry : transaction.itemGroupMap.values()) {
                    List<ItemGroup> itemGroupList = (List<ItemGroup>) itemGroupListEntry;
                    for (ItemGroup itemGroup : itemGroupList) {
                        itemGroupData.add(Arrays.asList((Object) itemGroup.idItemGroup, itemGroup.nameItemGroup));
                        parentGroupData.add(Arrays.asList((Object) itemGroup.idItemGroup, itemGroup.idParentItemGroup));
                    }
                }
            }
            
            if(transaction instanceof TransactionCashRegisterInfo) {
                for(ItemInfo itemInfo : itemsListTransaction) {
                    CashRegisterItemInfo item = (CashRegisterItemInfo) itemInfo;
                    Boolean notPromotionItem = item.notPromotionItem ? true : null;
                    if (itemFashionLM == null)
                        cashRegisterData.add(Arrays.asList((Object) idTransaction, transaction.nppGroupMachinery, dateTransaction, timeTransaction, snapshotTransaction,
                                transaction.description, item.idItem, item.idBarcode, item.name, item.idBrand, item.nameBrand,
                                item.price, format(item.splitItem), item.daysExpiry, item.expiryDate, item.description, item.pluNumber, item.flags, item.idUOM, 
                                item.shortNameUOM, format(item.passScalesItem), item.vat, notPromotionItem, item.idItemGroup, true));
                    else
                        cashRegisterData.add(Arrays.asList((Object) idTransaction, transaction.nppGroupMachinery, dateTransaction, timeTransaction, snapshotTransaction,
                                transaction.description, item.idItem, item.idBarcode, item.name, item.idBrand, item.nameBrand, item.idSeason, item.nameSeason,
                                item.price, format(item.splitItem), item.daysExpiry, item.expiryDate, item.description, item.pluNumber, item.flags, item.idUOM, 
                                item.shortNameUOM, format(item.passScalesItem), item.vat, notPromotionItem, item.idItemGroup, true));
                }
            } else if(transaction instanceof TransactionScalesInfo) {               
                for(ItemInfo itemInfo : itemsListTransaction) {
                    ScalesItemInfo item = (ScalesItemInfo) itemInfo;
                    scalesData.add(Arrays.asList((Object) idTransaction, transaction.nppGroupMachinery, dateTransaction, timeTransaction, snapshotTransaction,
                            transaction.description, item.idItem, item.idBarcode, item.name, item.price, format(item.splitItem), item.daysExpiry, item.hoursExpiry, item.expiryDate,
                            item.description, item.pluNumber, item.flags, format(item.passScalesItem), item.vat, item.idUOM, item.shortNameUOM, item.labelFormat, item.idItemGroup, true));
                }
            } else if(transaction instanceof TransactionTerminalInfo) {               
                for(ItemInfo item : itemsListTransaction) {
                    terminalData.add(Arrays.asList((Object) idTransaction, transaction.nppGroupMachinery, dateTransaction, timeTransaction, snapshotTransaction,
                            transaction.description, item.idItem, item.idBarcode, item.name, format(item.splitItem), item.daysExpiry,
                            item.pluNumber, item.flags, item.price, true));
                }
            } else if(transaction instanceof TransactionPriceCheckerInfo) {
                for(ItemInfo item : itemsListTransaction) {
                    priceCheckerData.add(Arrays.asList((Object) idTransaction, transaction.nppGroupMachinery, dateTransaction, timeTransaction, snapshotTransaction,
                            transaction.description, item.idItem, item.idBarcode, item.name, format(item.splitItem), item.daysExpiry, 
                            item.pluNumber, item.flags, item.price, true));
                }
            }
        }
        return Arrays.asList(itemGroupData, parentGroupData, cashRegisterData, scalesData, terminalData, priceCheckerData);
    }

    private void importItemGroups(ExecutionContext context, List<List<Object>> data) throws SQLException, ScriptingErrorLog.SemanticErrorException, SQLHandledException {

        if (notNullNorEmpty(data)) {

            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();

            ImportField idItemGroupField = new ImportField(findProperty("idItemGroup"));
            ImportKey<?> itemGroupKey = new ImportKey((ConcreteCustomClass) findClass("ItemGroup"),
                    findProperty("itemGroupId").getMapping(idItemGroupField));
            keys.add(itemGroupKey);
            props.add(new ImportProperty(idItemGroupField, findProperty("idItemGroup").getMapping(itemGroupKey)));
            fields.add(idItemGroupField);

            ImportField itemGroupNameField = new ImportField(findProperty("nameItemGroup"));
            props.add(new ImportProperty(itemGroupNameField, findProperty("nameItemGroup").getMapping(itemGroupKey)));
            fields.add(itemGroupNameField);

            ImportTable table = new ImportTable(fields, data);

            DataSession session = context.createSession();
            session.pushVolatileStats("TE_IG");
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            session.apply(context);
            session.popVolatileStats();
            session.close();
        }
    }

    private void importParentGroups(ExecutionContext context, List<List<Object>> data) throws ScriptingErrorLog.SemanticErrorException, SQLHandledException, SQLException {
        if (notNullNorEmpty(data)) {
            
            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();
            
            ImportField idItemGroupField = new ImportField(findProperty("idItemGroup"));
            ImportKey<?> itemGroupKey = new ImportKey((ConcreteCustomClass) findClass("ItemGroup"),
                    findProperty("itemGroupId").getMapping(idItemGroupField));
            keys.add(itemGroupKey);
            fields.add(idItemGroupField);

            ImportField idParentGroupField = new ImportField(findProperty("idItemGroup"));
            ImportKey<?> parentGroupKey = new ImportKey((ConcreteCustomClass) findClass("ItemGroup"),
                    findProperty("itemGroupId").getMapping(idParentGroupField));
            keys.add(parentGroupKey);
            props.add(new ImportProperty(idParentGroupField, findProperty("parentItemGroup").getMapping(itemGroupKey),
                    LM.object(findClass("ItemGroup")).getMapping(parentGroupKey)));
            fields.add(idParentGroupField);

            ImportTable table = new ImportTable(fields, data);

            DataSession session = context.createSession();
            session.pushVolatileStats("TE_PG");
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            session.apply(context);
            session.popVolatileStats();
            session.close();
        }
    }

    

    private void importCashRegisterTransactionList(ExecutionContext context, List<List<Object>> cashRegisterData) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {

        if (notNullNorEmpty(cashRegisterData)) {

            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();

            ImportField idMachineryPriceTransactionField = new ImportField(findProperty("idMachineryPriceTransaction"));
            ImportKey<?> machineryPriceTransactionKey = new ImportKey((CustomClass) findClass("CashRegisterPriceTransaction"),
                    findProperty("machineryPriceTransactionId").getMapping(idMachineryPriceTransactionField));
            keys.add(machineryPriceTransactionKey);
            props.add(new ImportProperty(idMachineryPriceTransactionField, findProperty("idMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(idMachineryPriceTransactionField);

            ImportField nppGroupMachineryField = new ImportField(findProperty("nppGroupMachinery"));
            ImportKey<?> groupCashRegisterKey = new ImportKey((CustomClass) findClass("GroupCashRegister"),
                    findProperty("groupCashRegisterNpp").getMapping(nppGroupMachineryField));
            groupCashRegisterKey.skipKey = true;
            keys.add(groupCashRegisterKey);
            props.add(new ImportProperty(nppGroupMachineryField, findProperty("groupMachineryMachineryPriceTransaction").getMapping(machineryPriceTransactionKey),
                    object(findClass("GroupCashRegister")).getMapping(groupCashRegisterKey)));
            fields.add(nppGroupMachineryField);

            ImportField dateMachineryPriceTransactionField = new ImportField(findProperty("dateMachineryPriceTransaction"));
            props.add(new ImportProperty(dateMachineryPriceTransactionField, findProperty("dateMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(dateMachineryPriceTransactionField);

            ImportField timeMachineryPriceTransactionField = new ImportField(findProperty("timeMachineryPriceTransaction"));
            props.add(new ImportProperty(timeMachineryPriceTransactionField, findProperty("timeMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(timeMachineryPriceTransactionField);

            ImportField snapshotMachineryPriceTransactionField = new ImportField(findProperty("snapshotMachineryPriceTransaction"));
            props.add(new ImportProperty(snapshotMachineryPriceTransactionField, findProperty("snapshotMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(snapshotMachineryPriceTransactionField);

            ImportField commentMachineryPriceTransactionField = new ImportField(findProperty("commentMachineryPriceTransaction"));
            props.add(new ImportProperty(commentMachineryPriceTransactionField, findProperty("commentMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(commentMachineryPriceTransactionField);

            ImportField idItemField = new ImportField(findProperty("idItem"));
            ImportKey<?> itemKey = new ImportKey((ConcreteCustomClass) findClass("Item"),
                    findProperty("itemId").getMapping(idItemField));
            keys.add(itemKey);
            props.add(new ImportProperty(idItemField, findProperty("idItem").getMapping(itemKey)));
            fields.add(idItemField);
            
            ImportField extIdBarcodeField = new ImportField(findProperty("extIdBarcode"));
            ImportKey<?> barcodeKey = new ImportKey((ConcreteCustomClass) findClass("Barcode"),
                    findProperty("extBarcodeId").getMapping(extIdBarcodeField));
            keys.add(barcodeKey);
            props.add(new ImportProperty(idItemField, findProperty("skuBarcode").getMapping(barcodeKey),
                    object(findClass("Item")).getMapping(itemKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("extIdBarcode").getMapping(barcodeKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("idBarcode").getMapping(barcodeKey)));
            fields.add(extIdBarcodeField);

            ImportField captionItemField = new ImportField(findProperty("captionItem"));
            props.add(new ImportProperty(captionItemField, findProperty("captionItem").getMapping(itemKey), true));
            props.add(new ImportProperty(captionItemField, findProperty("nameMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(captionItemField);

            ImportField idBrandField = new ImportField(findProperty("idBrand"));
            ImportKey<?> brandKey = new ImportKey((ConcreteCustomClass) findClass("Brand"),
                    findProperty("brandId").getMapping(idBrandField));
            keys.add(brandKey);
            props.add(new ImportProperty(idBrandField, findProperty("idBrand").getMapping(brandKey)));
            props.add(new ImportProperty(idBrandField, findProperty("brandItem").getMapping(itemKey),
                    object(findClass("Brand")).getMapping(brandKey), true));
            fields.add(idBrandField);

            ImportField nameBrandField = new ImportField(findProperty("nameBrand"));
            props.add(new ImportProperty(nameBrandField, findProperty("nameBrand").getMapping(brandKey), true));
            fields.add(nameBrandField);

            if(itemFashionLM != null) {
                ImportField idSeasonField = new ImportField(itemFashionLM.findProperty("idSeason"));
                ImportKey<?> seasonKey = new ImportKey((ConcreteCustomClass) itemFashionLM.findClass("Season"),
                        itemFashionLM.findProperty("seasonId").getMapping(idSeasonField));
                props.add(new ImportProperty(idSeasonField, itemFashionLM.findProperty("idSeason").getMapping(seasonKey)));
                keys.add(seasonKey);
                props.add(new ImportProperty(idSeasonField, itemFashionLM.findProperty("seasonItem").getMapping(itemKey),
                        object(itemFashionLM.findClass("Season")).getMapping(seasonKey), true));
                fields.add(idSeasonField);

                ImportField nameSeasonField = new ImportField(itemFashionLM.findProperty("nameSeason"));
                props.add(new ImportProperty(nameSeasonField, itemFashionLM.findProperty("nameSeason").getMapping(seasonKey), true));
                fields.add(nameSeasonField);
            }
            
            ImportField priceMachineryPriceTransactionBarcodeField = new ImportField(findProperty("priceMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(priceMachineryPriceTransactionBarcodeField, findProperty("priceMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(priceMachineryPriceTransactionBarcodeField);

            ImportField splitMachineryPriceTransactionBarcodeField = new ImportField(findProperty("splitMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(splitMachineryPriceTransactionBarcodeField, findProperty("splitMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(splitMachineryPriceTransactionBarcodeField);

            ImportField expiryDaysMachineryPriceTransactionBarcodeField = new ImportField(findProperty("expiryDaysMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(expiryDaysMachineryPriceTransactionBarcodeField, findProperty("expiryDaysMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(expiryDaysMachineryPriceTransactionBarcodeField);
            
            ImportField expiryDateMachineryPriceTransactionBarcodeField = new ImportField(findProperty("expiryDateMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(expiryDateMachineryPriceTransactionBarcodeField, findProperty("expiryDateMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(expiryDateMachineryPriceTransactionBarcodeField);

            ImportField descriptionMachineryPriceTransactionBarcodeField = new ImportField(findProperty("descriptionMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(descriptionMachineryPriceTransactionBarcodeField, findProperty("descriptionMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(descriptionMachineryPriceTransactionBarcodeField);

            ImportField pluNumberMachineryPriceTransactionBarcodeField = new ImportField(findProperty("pluNumberMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(pluNumberMachineryPriceTransactionBarcodeField, findProperty("pluNumberMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(pluNumberMachineryPriceTransactionBarcodeField);

            ImportField flagsMachineryPriceTransactionBarcodeField = new ImportField(findProperty("flagsMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(flagsMachineryPriceTransactionBarcodeField, findProperty("flagsMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(flagsMachineryPriceTransactionBarcodeField);

            ImportField idUOMField = new ImportField(findProperty("idUOM"));
            ImportKey<?> UOMKey = new ImportKey((ConcreteCustomClass) findClass("UOM"),
                    findProperty("UOMId").getMapping(idUOMField));
            UOMKey.skipKey = true;
            keys.add(UOMKey);
            props.add(new ImportProperty(idUOMField, findProperty("idUOM").getMapping(UOMKey)));
            props.add(new ImportProperty(idUOMField, findProperty("idUOMMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(idUOMField);

            ImportField shortNameUOMField = new ImportField(findProperty("shortNameUOM"));
            props.add(new ImportProperty(shortNameUOMField, findProperty("shortNameUOM").getMapping(UOMKey), true));
            props.add(new ImportProperty(shortNameUOMField, findProperty("shortNameUOMMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(shortNameUOMField);

            ImportField passScalesMachineryPriceTransactionBarcodeField = new ImportField(findProperty("passScalesMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(passScalesMachineryPriceTransactionBarcodeField, findProperty("passScalesMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(passScalesMachineryPriceTransactionBarcodeField);

            DataObject defaultCountryObject = (DataObject) findProperty("defaultCountry").readClasses(context);
            ImportField valueVATItemCountryDateField = new ImportField(findProperty("valueVATItemCountryDate"));
            ImportKey<?> VATKey = new ImportKey((ConcreteCustomClass) findClass("Range"),
                    findProperty("valueCurrentVATDefaultValue").getMapping(valueVATItemCountryDateField));
            VATKey.skipKey = true;
            keys.add(VATKey);
            props.add(new ImportProperty(valueVATItemCountryDateField, findProperty("VATItemCountry").getMapping(itemKey, defaultCountryObject),
                    object(findClass("Range")).getMapping(VATKey)));
            if(machineryPriceTransactionStockTaxLM != null)
                props.add(new ImportProperty(valueVATItemCountryDateField, machineryPriceTransactionStockTaxLM.findProperty("VATMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(valueVATItemCountryDateField);

            ImportField notPromotionItemField = new ImportField(findProperty("notPromotionItem"));
            props.add(new ImportProperty(notPromotionItemField, findProperty("notPromotionItem").getMapping(itemKey)));
            fields.add(notPromotionItemField);

            ImportField idItemGroupField = new ImportField(findProperty("idItemGroup"));
            ImportKey<?> itemGroupKey = new ImportKey((ConcreteCustomClass) findClass("ItemGroup"),
                    findProperty("itemGroupId").getMapping(idItemGroupField));
            keys.add(itemGroupKey);
            props.add(new ImportProperty(idItemGroupField, findProperty("skuGroupMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey),
                    object(findClass("ItemGroup")).getMapping(itemGroupKey)));
            props.add(new ImportProperty(idItemGroupField, findProperty("itemGroupItem").getMapping(itemKey),
                    object(findClass("ItemGroup")).getMapping(itemGroupKey)));
            fields.add(idItemGroupField);
                        
            ImportField inMachineryPriceTransactionBarcodeField = new ImportField(findProperty("inMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(inMachineryPriceTransactionBarcodeField, findProperty("inMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(inMachineryPriceTransactionBarcodeField);
            
            ImportTable table = new ImportTable(fields, cashRegisterData);

            DataSession session = context.createSession();
            session.pushVolatileStats("TE_CT");
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            session.apply(context);
            session.popVolatileStats();
            session.close();
        }
    }

    private void importScalesTransactionList(ExecutionContext context, List<List<Object>> scalesData) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {

        if (notNullNorEmpty(scalesData)) {

            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();

            
            ImportField idMachineryPriceTransactionField = new ImportField(findProperty("idMachineryPriceTransaction"));
            ImportKey<?> machineryPriceTransactionKey = new ImportKey((CustomClass) findClass("ScalesPriceTransaction"),
                    findProperty("machineryPriceTransactionId").getMapping(idMachineryPriceTransactionField));
            keys.add(machineryPriceTransactionKey);
            props.add(new ImportProperty(idMachineryPriceTransactionField, findProperty("idMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(idMachineryPriceTransactionField);

            ImportField nppGroupMachineryField = new ImportField(findProperty("nppGroupMachinery"));
            ImportKey<?> groupScalesKey = new ImportKey((CustomClass) findClass("GroupScales"),
                    findProperty("groupScalesNpp").getMapping(nppGroupMachineryField));
            groupScalesKey.skipKey = true;
            keys.add(groupScalesKey);
            props.add(new ImportProperty(nppGroupMachineryField, findProperty("groupMachineryMachineryPriceTransaction").getMapping(machineryPriceTransactionKey),
                    object(findClass("GroupScales")).getMapping(groupScalesKey)));
            fields.add(nppGroupMachineryField);

            ImportField dateMachineryPriceTransactionField = new ImportField(findProperty("dateMachineryPriceTransaction"));
            props.add(new ImportProperty(dateMachineryPriceTransactionField, findProperty("dateMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(dateMachineryPriceTransactionField);

            ImportField timeMachineryPriceTransactionField = new ImportField(findProperty("timeMachineryPriceTransaction"));
            props.add(new ImportProperty(timeMachineryPriceTransactionField, findProperty("timeMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(timeMachineryPriceTransactionField);

            ImportField snapshotMachineryPriceTransactionField = new ImportField(findProperty("snapshotMachineryPriceTransaction"));
            props.add(new ImportProperty(snapshotMachineryPriceTransactionField, findProperty("snapshotMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(snapshotMachineryPriceTransactionField);

            ImportField commentMachineryPriceTransactionField = new ImportField(findProperty("commentMachineryPriceTransaction"));
            props.add(new ImportProperty(commentMachineryPriceTransactionField, findProperty("commentMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(commentMachineryPriceTransactionField);

            ImportField idItemField = new ImportField(findProperty("idItem"));
            ImportKey<?> itemKey = new ImportKey((ConcreteCustomClass) findClass("Item"),
                    findProperty("itemId").getMapping(idItemField));
            keys.add(itemKey);
            props.add(new ImportProperty(idItemField, findProperty("idItem").getMapping(itemKey)));
            fields.add(idItemField);

            ImportField extIdBarcodeField = new ImportField(findProperty("extIdBarcode"));
            ImportKey<?> barcodeKey = new ImportKey((ConcreteCustomClass) findClass("Barcode"),
                    findProperty("extBarcodeId").getMapping(extIdBarcodeField));
            keys.add(barcodeKey);
            props.add(new ImportProperty(idItemField, findProperty("skuBarcode").getMapping(barcodeKey),
                    object(findClass("Item")).getMapping(itemKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("extIdBarcode").getMapping(barcodeKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("idBarcode").getMapping(barcodeKey)));
            fields.add(extIdBarcodeField);
            
            ImportField captionItemField = new ImportField(findProperty("captionItem"));
            props.add(new ImportProperty(captionItemField, findProperty("captionItem").getMapping(itemKey), true));
            props.add(new ImportProperty(captionItemField, findProperty("nameMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(captionItemField);

            ImportField priceMachineryPriceTransactionBarcodeField = new ImportField(findProperty("priceMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(priceMachineryPriceTransactionBarcodeField, findProperty("priceMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(priceMachineryPriceTransactionBarcodeField);
            
            ImportField splitMachineryPriceTransactionBarcodeField = new ImportField(findProperty("splitMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(splitMachineryPriceTransactionBarcodeField, findProperty("splitMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(splitMachineryPriceTransactionBarcodeField);

            ImportField expiryDaysMachineryPriceTransactionBarcodeField = new ImportField(findProperty("expiryDaysMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(expiryDaysMachineryPriceTransactionBarcodeField, findProperty("expiryDaysMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(expiryDaysMachineryPriceTransactionBarcodeField);

            ImportField hoursExpiryMachineryPriceTransactionBarcodeField = new ImportField(findProperty("hoursExpiryMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(hoursExpiryMachineryPriceTransactionBarcodeField, findProperty("hoursExpiryMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(hoursExpiryMachineryPriceTransactionBarcodeField);

            ImportField expiryDateMachineryPriceTransactionBarcodeField = new ImportField(findProperty("expiryDateMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(expiryDateMachineryPriceTransactionBarcodeField, findProperty("expiryDateMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(expiryDateMachineryPriceTransactionBarcodeField);
            
            ImportField descriptionMachineryPriceTransactionBarcodeField = new ImportField(findProperty("descriptionMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(descriptionMachineryPriceTransactionBarcodeField, findProperty("descriptionMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(descriptionMachineryPriceTransactionBarcodeField);

            ImportField pluNumberMachineryPriceTransactionBarcodeField = new ImportField(findProperty("pluNumberMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(pluNumberMachineryPriceTransactionBarcodeField, findProperty("pluNumberMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(pluNumberMachineryPriceTransactionBarcodeField);

            ImportField flagsMachineryPriceTransactionBarcodeField = new ImportField(findProperty("flagsMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(flagsMachineryPriceTransactionBarcodeField, findProperty("flagsMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(flagsMachineryPriceTransactionBarcodeField);
            
            ImportField passScalesMachineryPriceTransactionBarcodeField = new ImportField(findProperty("passScalesMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(passScalesMachineryPriceTransactionBarcodeField, findProperty("passScalesMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(passScalesMachineryPriceTransactionBarcodeField);

            DataObject defaultCountryObject = (DataObject) findProperty("defaultCountry").readClasses(context);
            ImportField valueVATItemCountryDateField = new ImportField(findProperty("valueVATItemCountryDate"));
            ImportKey<?> VATKey = new ImportKey((ConcreteCustomClass) findClass("Range"),
                    findProperty("valueCurrentVATDefaultValue").getMapping(valueVATItemCountryDateField));
            VATKey.skipKey = true;
            keys.add(VATKey);
            props.add(new ImportProperty(valueVATItemCountryDateField, findProperty("VATItemCountry").getMapping(itemKey, defaultCountryObject),
                    object(findClass("Range")).getMapping(VATKey)));
            if(machineryPriceTransactionStockTaxLM != null)
                props.add(new ImportProperty(valueVATItemCountryDateField, machineryPriceTransactionStockTaxLM.findProperty("VATMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(valueVATItemCountryDateField);
            
            ImportField idUOMField = new ImportField(findProperty("idUOM"));
            ImportKey<?> UOMKey = new ImportKey((ConcreteCustomClass) findClass("UOM"),
                    findProperty("UOMId").getMapping(idUOMField));
            UOMKey.skipKey = true;
            keys.add(UOMKey);
            props.add(new ImportProperty(idUOMField, findProperty("idUOM").getMapping(UOMKey)));
            props.add(new ImportProperty(idUOMField, findProperty("idUOMMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(idUOMField);

            ImportField shortNameUOMField = new ImportField(findProperty("shortNameUOM"));
            props.add(new ImportProperty(shortNameUOMField, findProperty("shortNameUOM").getMapping(UOMKey), true));
            props.add(new ImportProperty(shortNameUOMField, findProperty("shortNameUOMMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(shortNameUOMField);
            
            ImportField labelFormatMachineryPriceTransactionBarcodeField = new ImportField(findProperty("labelFormatMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(labelFormatMachineryPriceTransactionBarcodeField, findProperty("labelFormatMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(labelFormatMachineryPriceTransactionBarcodeField);

            ImportField idItemGroupField = new ImportField(findProperty("idItemGroup"));
            ImportKey<?> itemGroupKey = new ImportKey((ConcreteCustomClass) findClass("ItemGroup"),
                    findProperty("itemGroupId").getMapping(idItemGroupField));
            keys.add(itemGroupKey);
            props.add(new ImportProperty(idItemGroupField, findProperty("skuGroupMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey),
                    object(findClass("ItemGroup")).getMapping(itemGroupKey)));
            props.add(new ImportProperty(idItemGroupField, findProperty("itemGroupItem").getMapping(itemKey),
                    object(findClass("ItemGroup")).getMapping(itemGroupKey)));
            fields.add(idItemGroupField);
            
            ImportField inMachineryPriceTransactionBarcodeField = new ImportField(findProperty("inMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(inMachineryPriceTransactionBarcodeField, findProperty("inMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(inMachineryPriceTransactionBarcodeField);

            ImportTable table = new ImportTable(fields, scalesData);

            DataSession session = context.createSession();
            session.pushVolatileStats("TE_ST");
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            session.apply(context);
            session.popVolatileStats();
            session.close();
        }
    }

    private void importTerminalTransactionList(ExecutionContext context, List<List<Object>> terminalData) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {

        if (notNullNorEmpty(terminalData)) {

            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();

            ImportField idMachineryPriceTransactionField = new ImportField(findProperty("idMachineryPriceTransaction"));
            ImportKey<?> machineryPriceTransactionKey = new ImportKey((CustomClass) findClass("TerminalPriceTransaction"),
                    findProperty("machineryPriceTransactionId").getMapping(idMachineryPriceTransactionField));
            keys.add(machineryPriceTransactionKey);
            props.add(new ImportProperty(idMachineryPriceTransactionField, findProperty("idMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(idMachineryPriceTransactionField);

            ImportField nppGroupMachineryField = new ImportField(findProperty("nppGroupMachinery"));
            ImportKey<?> groupTerminalKey = new ImportKey((CustomClass) findClass("GroupTerminal"),
                    findProperty("groupTerminalNpp").getMapping(nppGroupMachineryField));
            groupTerminalKey.skipKey = true;
            keys.add(groupTerminalKey);
            props.add(new ImportProperty(nppGroupMachineryField, findProperty("groupMachineryMachineryPriceTransaction").getMapping(machineryPriceTransactionKey),
                    object(findClass("GroupTerminal")).getMapping(groupTerminalKey)));
            fields.add(nppGroupMachineryField);

            ImportField dateMachineryPriceTransactionField = new ImportField(findProperty("dateMachineryPriceTransaction"));
            props.add(new ImportProperty(dateMachineryPriceTransactionField, findProperty("dateMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(dateMachineryPriceTransactionField);

            ImportField timeMachineryPriceTransactionField = new ImportField(findProperty("timeMachineryPriceTransaction"));
            props.add(new ImportProperty(timeMachineryPriceTransactionField, findProperty("timeMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(timeMachineryPriceTransactionField);

            ImportField snapshotMachineryPriceTransactionField = new ImportField(findProperty("snapshotMachineryPriceTransaction"));
            props.add(new ImportProperty(snapshotMachineryPriceTransactionField, findProperty("snapshotMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(snapshotMachineryPriceTransactionField);

            ImportField commentMachineryPriceTransactionField = new ImportField(findProperty("commentMachineryPriceTransaction"));
            props.add(new ImportProperty(commentMachineryPriceTransactionField, findProperty("commentMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(commentMachineryPriceTransactionField);

            ImportField idItemField = new ImportField(findProperty("idItem"));
            ImportKey<?> itemKey = new ImportKey((ConcreteCustomClass) findClass("Item"),
                    findProperty("itemId").getMapping(idItemField));
            keys.add(itemKey);
            props.add(new ImportProperty(idItemField, findProperty("idItem").getMapping(itemKey)));
            fields.add(idItemField);
            
            ImportField extIdBarcodeField = new ImportField(findProperty("extIdBarcode"));
            ImportKey<?> barcodeKey = new ImportKey((ConcreteCustomClass) findClass("Barcode"),
                    findProperty("extBarcodeId").getMapping(extIdBarcodeField));
            keys.add(barcodeKey);
            props.add(new ImportProperty(idItemField, findProperty("skuBarcode").getMapping(barcodeKey),
                    object(findClass("Item")).getMapping(itemKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("extIdBarcode").getMapping(barcodeKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("idBarcode").getMapping(barcodeKey)));
            fields.add(extIdBarcodeField);

            ImportField captionItemField = new ImportField(findProperty("captionItem"));
            props.add(new ImportProperty(captionItemField, findProperty("captionItem").getMapping(itemKey), true));
            props.add(new ImportProperty(captionItemField, findProperty("nameMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(captionItemField);

            ImportField splitMachineryPriceTransactionBarcodeField = new ImportField(findProperty("splitMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(splitMachineryPriceTransactionBarcodeField, findProperty("splitMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(splitMachineryPriceTransactionBarcodeField);

            ImportField expiryDaysMachineryPriceTransactionBarcodeField = new ImportField(findProperty("expiryDaysMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(expiryDaysMachineryPriceTransactionBarcodeField, findProperty("expiryDaysMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(expiryDaysMachineryPriceTransactionBarcodeField);

            ImportField pluNumberMachineryPriceTransactionBarcodeField = new ImportField(findProperty("pluNumberMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(pluNumberMachineryPriceTransactionBarcodeField, findProperty("pluNumberMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(pluNumberMachineryPriceTransactionBarcodeField);

            ImportField flagsMachineryPriceTransactionBarcodeField = new ImportField(findProperty("flagsMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(flagsMachineryPriceTransactionBarcodeField, findProperty("flagsMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(flagsMachineryPriceTransactionBarcodeField);
            
            ImportField priceMachineryPriceTransactionBarcodeField = new ImportField(findProperty("priceMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(priceMachineryPriceTransactionBarcodeField, findProperty("priceMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(priceMachineryPriceTransactionBarcodeField);
            
            ImportField inMachineryPriceTransactionBarcodeField = new ImportField(findProperty("inMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(inMachineryPriceTransactionBarcodeField, findProperty("inMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(inMachineryPriceTransactionBarcodeField);

            ImportTable table = new ImportTable(fields, terminalData);

            DataSession session = context.createSession();
            session.pushVolatileStats("TE_TT");
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            session.apply(context);
            session.popVolatileStats();
            session.close();
        }
    }

    private void importPriceCheckerTransactionList(ExecutionContext context, List<List<Object>> priceCheckerData) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {

        if (notNullNorEmpty(priceCheckerData)) {

            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();

            ImportField idMachineryPriceTransactionField = new ImportField(findProperty("idMachineryPriceTransaction"));
            ImportKey<?> machineryPriceTransactionKey = new ImportKey((CustomClass) findClass("ScalesPriceTransaction"),
                    findProperty("machineryPriceTransactionId").getMapping(idMachineryPriceTransactionField));
            keys.add(machineryPriceTransactionKey);
            props.add(new ImportProperty(idMachineryPriceTransactionField, findProperty("idMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(idMachineryPriceTransactionField);

            ImportField nppGroupMachineryField = new ImportField(findProperty("nppGroupMachinery"));
            ImportKey<?> groupPriceCheckerKey = new ImportKey((CustomClass) findClass("GroupPriceChecker"),
                    findProperty("groupPriceCheckerNpp").getMapping(nppGroupMachineryField));
            groupPriceCheckerKey.skipKey = true;
            keys.add(groupPriceCheckerKey);
            props.add(new ImportProperty(nppGroupMachineryField, findProperty("groupMachineryMachineryPriceTransaction").getMapping(machineryPriceTransactionKey),
                    object(findClass("GroupPriceChecker")).getMapping(groupPriceCheckerKey)));
            fields.add(nppGroupMachineryField);

            ImportField dateMachineryPriceTransactionField = new ImportField(findProperty("dateMachineryPriceTransaction"));
            props.add(new ImportProperty(dateMachineryPriceTransactionField, findProperty("dateMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(dateMachineryPriceTransactionField);

            ImportField timeMachineryPriceTransactionField = new ImportField(findProperty("timeMachineryPriceTransaction"));
            props.add(new ImportProperty(timeMachineryPriceTransactionField, findProperty("timeMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(timeMachineryPriceTransactionField);

            ImportField snapshotMachineryPriceTransactionField = new ImportField(findProperty("snapshotMachineryPriceTransaction"));
            props.add(new ImportProperty(snapshotMachineryPriceTransactionField, findProperty("snapshotMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(snapshotMachineryPriceTransactionField);

            ImportField commentMachineryPriceTransactionField = new ImportField(findProperty("commentMachineryPriceTransaction"));
            props.add(new ImportProperty(commentMachineryPriceTransactionField, findProperty("commentMachineryPriceTransaction").getMapping(machineryPriceTransactionKey)));
            fields.add(commentMachineryPriceTransactionField);

            ImportField idItemField = new ImportField(findProperty("idItem"));
            ImportKey<?> itemKey = new ImportKey((ConcreteCustomClass) findClass("Item"),
                    findProperty("itemId").getMapping(idItemField));
            keys.add(itemKey);
            props.add(new ImportProperty(idItemField, findProperty("idItem").getMapping(itemKey)));
            fields.add(idItemField);
            
            ImportField extIdBarcodeField = new ImportField(findProperty("extIdBarcode"));
            ImportKey<?> barcodeKey = new ImportKey((ConcreteCustomClass) findClass("Barcode"),
                    findProperty("extBarcodeId").getMapping(extIdBarcodeField));
            keys.add(barcodeKey);
            props.add(new ImportProperty(idItemField, findProperty("skuBarcode").getMapping(barcodeKey),
                    object(findClass("Item")).getMapping(itemKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("extIdBarcode").getMapping(barcodeKey)));
            props.add(new ImportProperty(extIdBarcodeField, findProperty("idBarcode").getMapping(barcodeKey)));
            fields.add(extIdBarcodeField);

            ImportField captionItemField = new ImportField(findProperty("captionItem"));
            props.add(new ImportProperty(captionItemField, findProperty("captionItem").getMapping(itemKey), true));
            props.add(new ImportProperty(captionItemField, findProperty("nameMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(captionItemField);

            ImportField splitMachineryPriceTransactionBarcodeField = new ImportField(findProperty("splitMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(splitMachineryPriceTransactionBarcodeField, findProperty("splitMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(splitMachineryPriceTransactionBarcodeField);

            ImportField expiryDaysMachineryPriceTransactionBarcodeField = new ImportField(findProperty("expiryDaysMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(expiryDaysMachineryPriceTransactionBarcodeField, findProperty("expiryDaysMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(expiryDaysMachineryPriceTransactionBarcodeField);

            ImportField pluNumberMachineryPriceTransactionBarcodeField = new ImportField(findProperty("pluNumberMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(pluNumberMachineryPriceTransactionBarcodeField, findProperty("pluNumberMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(pluNumberMachineryPriceTransactionBarcodeField);

            ImportField flagsMachineryPriceTransactionBarcodeField = new ImportField(findProperty("flagsMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(flagsMachineryPriceTransactionBarcodeField, findProperty("flagsMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(flagsMachineryPriceTransactionBarcodeField);
            
            ImportField priceMachineryPriceTransactionBarcodeField = new ImportField(findProperty("priceMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(priceMachineryPriceTransactionBarcodeField, findProperty("priceMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(priceMachineryPriceTransactionBarcodeField);
            
            ImportField inMachineryPriceTransactionBarcodeField = new ImportField(findProperty("inMachineryPriceTransactionBarcode"));
            props.add(new ImportProperty(inMachineryPriceTransactionBarcodeField, findProperty("inMachineryPriceTransactionBarcode").getMapping(machineryPriceTransactionKey, barcodeKey)));
            fields.add(inMachineryPriceTransactionBarcodeField);
            
            ImportTable table = new ImportTable(fields, priceCheckerData);

            DataSession session = context.createSession();
            session.pushVolatileStats("TE_PT");
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            session.apply(context);
            session.popVolatileStats();
            session.close();
        }
    }
    
    private Boolean format(Boolean value) {
        return value != null && value ? true : null;
    }



}

