package equ.srv;

import com.google.common.base.Throwables;
import equ.api.*;
import equ.api.terminal.*;
import lsfusion.base.DateConverter;
import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.interop.Compare;
import lsfusion.server.classes.ConcreteClass;
import lsfusion.server.classes.ConcreteCustomClass;
import lsfusion.server.classes.CustomClass;
import lsfusion.server.classes.StringClass;
import lsfusion.server.context.ThreadLocalContext;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.integration.*;
import lsfusion.server.lifecycle.LifecycleAdapter;
import lsfusion.server.lifecycle.LifecycleEvent;
import lsfusion.server.logics.*;
import lsfusion.server.logics.linear.LCP;
import lsfusion.server.logics.property.PropertyInterface;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;
import org.apache.log4j.Logger;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;

public class EquipmentServer extends LifecycleAdapter implements EquipmentServerInterface, InitializingBean {
    private static final Logger logger = Logger.getLogger(EquipmentServer.class);

    public static final String EXPORT_NAME = "EquipmentServer";

    private LogicsInstance logicsInstance;

    private SoftCheckInterface softCheck;
    
    private ScriptingLogicsModule equLM;

    //Опциональные модули
    private ScriptingLogicsModule scalesItemLM;
    private ScriptingLogicsModule zReportLM;
    private ScriptingLogicsModule itemLM;
    private ScriptingLogicsModule discountCardLM;
    private ScriptingLogicsModule cashRegisterLM;
    private ScriptingLogicsModule scalesLM;
    private ScriptingLogicsModule priceCheckerLM;
    private ScriptingLogicsModule terminalLM;
    private ScriptingLogicsModule purchaseOrderLM;

    private boolean started = false;

    public void setLogicsInstance(LogicsInstance logicsInstance) {
        this.logicsInstance = logicsInstance;
    }

    public LogicsInstance getLogicsInstance() {
        return logicsInstance;
    }

    public void setSoftCheckHandler(SoftCheckInterface softCheck) {
        this.softCheck = softCheck;
    }

    public SoftCheckInterface getSoftCheckHandler() {
        return softCheck;
    }
    
    public RMIManager getRmiManager() {
        return logicsInstance.getRmiManager();
    }

    public BusinessLogics getBusinessLogics() {
        return logicsInstance.getBusinessLogics();
    }

    public DBManager getDbManager() {
        return logicsInstance.getDbManager();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
    }

    @Override
    protected void onInit(LifecycleEvent event) {
        equLM = (ScriptingLogicsModule) getBusinessLogics().getModule("Equipment");
        Assert.notNull(equLM, "can't find Equipment module");
        scalesItemLM = (ScriptingLogicsModule) getBusinessLogics().getModule("ScalesItem");
        zReportLM = (ScriptingLogicsModule) getBusinessLogics().getModule("ZReport");
        itemLM = (ScriptingLogicsModule) getBusinessLogics().getModule("Item");
        discountCardLM = (ScriptingLogicsModule) getBusinessLogics().getModule("DiscountCard");
        cashRegisterLM = (ScriptingLogicsModule) getBusinessLogics().getModule("EquipmentCashRegister");
        scalesLM = (ScriptingLogicsModule) getBusinessLogics().getModule("EquipmentScales");
        priceCheckerLM = (ScriptingLogicsModule) getBusinessLogics().getModule("EquipmentPriceChecker");
        terminalLM = (ScriptingLogicsModule) getBusinessLogics().getModule("EquipmentTerminal");
        purchaseOrderLM = (ScriptingLogicsModule) getBusinessLogics().getModule("PurchaseOrder");
    }

    @Override
    protected void onStarted(LifecycleEvent event) {
        logger.info("Binding Equipment Server.");
        try {
            getRmiManager().bindAndExport(EXPORT_NAME, this);
            started = true;
        } catch (Exception e) {
            throw new RuntimeException("Error exporting Equipment Server: ", e);
        }
    }

    @Override
    protected void onStopping(LifecycleEvent event) {
        if (started) {
            logger.info("Stopping Equipment Server.");
            try {
                getRmiManager().unbindAndUnexport(EXPORT_NAME, this);
            } catch (Exception e) {
                throw new RuntimeException("Error stopping Equipment Server: ", e);
            }
        }
    }

    @Override
    public List<SoftCheckInfo> readSoftCheckInfo() throws RemoteException, SQLException {
        return softCheck == null ? null : softCheck.readSoftCheckInfo();
    }

    @Override
    public void finishSoftCheckInfo(Set<String> invoiceSet) throws RemoteException, SQLException {
        if(softCheck != null)
            softCheck.finishSoftCheckInfo(invoiceSet);
    }

    @Override
    public String sendSucceededSoftCheckInfo(Set invoiceSet) throws RemoteException, SQLException {
        return softCheck == null ? null : softCheck.sendSucceededSoftCheckInfo(invoiceSet);
    }

    @Override
    public List<TransactionInfo> readTransactionInfo(String equServerID) throws RemoteException, SQLException {
        try {

            DataSession session = getDbManager().createSession();
            List<TransactionInfo> transactionList = new ArrayList<TransactionInfo>();

            LCP isMachineryPriceTransaction = equLM.is(equLM.findClassByCompoundName("MachineryPriceTransaction"));
            ImRevMap<Object, KeyExpr> keys = isMachineryPriceTransaction.getMapKeys();
            KeyExpr key = keys.singleValue();
            QueryBuilder<Object, Object> query = new QueryBuilder<Object, Object>(keys);

            String[] mptProperties = new String[]{"dateTimeMachineryPriceTransaction", "groupMachineryMachineryPriceTransaction",
                    "nppGroupMachineryMachineryPriceTransaction", "snapshotMachineryPriceTransaction"};
            for (String property : mptProperties) {
                query.addProperty(property, equLM.findLCPByCompoundOldName(property).getExpr(key));
            }
            query.and(equLM.findLCPByCompoundOldName("sidEquipmentServerMachineryPriceTransaction").getExpr(key).compare(new DataObject(equServerID, StringClass.get(20)), Compare.EQUALS));
            query.and(equLM.findLCPByCompoundOldName("processMachineryPriceTransaction").getExpr(key).getWhere());

            ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> result = query.executeClasses(session);
            List<Object[]> transactionObjects = new ArrayList<Object[]>();
            for (int i = 0, size = result.size(); i < size; i++) {
                ImMap<Object, ObjectValue> value = result.getValue(i);
                DataObject dateTimeMPT = (DataObject) value.get("dateTimeMachineryPriceTransaction");
                DataObject groupMachineryMPT = (DataObject) value.get("groupMachineryMachineryPriceTransaction");
                Integer nppGroupMachineryMPT = (Integer) value.get("nppGroupMachineryMachineryPriceTransaction").getValue();
                DataObject transactionObject = result.getKey(i).singleValue();
                Boolean snapshotMPT = value.get("snapshotMachineryPriceTransaction") instanceof DataObject;
                transactionObjects.add(new Object[]{groupMachineryMPT, nppGroupMachineryMPT, transactionObject, dateTimeCode((Timestamp) dateTimeMPT.getValue()), dateTimeMPT, snapshotMPT});
            }

            for (Object[] transaction : transactionObjects) {

                DataObject groupMachineryObject = (DataObject) transaction[0];
                Integer nppGroupMachinery = (Integer) transaction[1];
                DataObject transactionObject = (DataObject) transaction[2];
                String dateTimeCode = (String) transaction[3];
                Date date = new Date(((Timestamp) ((DataObject) transaction[4]).getValue()).getTime());
                Boolean snapshotTransaction = (Boolean) transaction[5];

                KeyExpr barcodeExpr = new KeyExpr("barcode");
                ImRevMap<Object, KeyExpr> skuKeys = MapFact.singletonRev((Object) "barcode", barcodeExpr);

                QueryBuilder<Object, Object> skuQuery = new QueryBuilder<Object, Object>(skuKeys);

                String[] skuProperties = new String[]{"nameMachineryPriceTransactionBarcode", "priceMachineryPriceTransactionBarcode",
                        "expiryDateMachineryPriceTransactionBarcode", "isWeightMachineryPriceTransactionBarcode", "skuGroupMachineryPriceTransactionBarcode"};
                String[] extraSkuProperties = new String[]{"daysExpiryMachineryPriceTransactionBarcode", "hoursExpiryMachineryPriceTransactionBarcode",
                        "labelFormatMachineryPriceTransactionBarcode", "compositionMachineryPriceTransactionBarcode"};
                skuQuery.addProperty("idBarcode", equLM.findLCPByCompoundOldName("idBarcode").getExpr(barcodeExpr));
                for (String property : skuProperties) {
                    skuQuery.addProperty(property, equLM.findLCPByCompoundOldName(property).getExpr(transactionObject.getExpr(), barcodeExpr));
                }
                if (scalesItemLM != null) {
                    for (String property : extraSkuProperties) {
                        skuQuery.addProperty(property, scalesItemLM.findLCPByCompoundOldName(property).getExpr(transactionObject.getExpr(), barcodeExpr));
                    }
                }

                skuQuery.and(equLM.findLCPByCompoundOldName("inMachineryPriceTransactionBarcode").getExpr(transactionObject.getExpr(), barcodeExpr).getWhere());

                ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> skuResult = skuQuery.executeClasses(session);                

                if (cashRegisterLM != null && transactionObject.objectClass.equals(cashRegisterLM.findClassByCompoundName("CashRegisterPriceTransaction"))) {
                    
                    String directoryGroupCashRegister = (String) cashRegisterLM.findLCPByCompoundOldName("directoryGroupCashRegister").read(session, groupMachineryObject);
                    java.sql.Date startDateGroupCashRegister = (java.sql.Date) cashRegisterLM.findLCPByCompoundOldName("startDateGroupCashRegister").read(session, groupMachineryObject);
                    
                    List<CashRegisterInfo> cashRegisterInfoList = new ArrayList<CashRegisterInfo>();
                    LCP<PropertyInterface> isCashRegister = (LCP<PropertyInterface>) cashRegisterLM.is(cashRegisterLM.findClassByCompoundName("CashRegister"));

                    ImRevMap<PropertyInterface, KeyExpr> cashRegisterKeys = isCashRegister.getMapKeys();
                    KeyExpr cashRegisterKey = cashRegisterKeys.singleValue();
                    QueryBuilder<PropertyInterface, Object> cashRegisterQuery = new QueryBuilder<PropertyInterface, Object>(cashRegisterKeys);

                    String[] cashRegisterProperties = new String[]{"nppMachinery", "portMachinery", "nameModelMachinery", "handlerModelMachinery"};
                    for (String property : cashRegisterProperties) {
                        cashRegisterQuery.addProperty(property, cashRegisterLM.findLCPByCompoundOldName(property).getExpr(cashRegisterKey));
                    }
                    cashRegisterQuery.and(isCashRegister.property.getExpr(cashRegisterKeys).getWhere());
                    cashRegisterQuery.and(cashRegisterLM.findLCPByCompoundOldName("groupCashRegisterCashRegister").getExpr(cashRegisterKey).compare(groupMachineryObject, Compare.EQUALS));

                    ImOrderMap<ImMap<PropertyInterface, Object>, ImMap<Object, Object>> cashRegisterResult = cashRegisterQuery.execute(session);

                    for (ImMap<Object, Object> row : cashRegisterResult.valueIt()) {
                        Integer nppMachinery = (Integer) row.get("nppMachinery");
                        String nameModel = (String) row.get("nameModelMachinery");
                        String handlerModel = (String) row.get("handlerModelMachinery");
                        String portMachinery = (String) row.get("portMachinery");
                        cashRegisterInfoList.add(new CashRegisterInfo(nppGroupMachinery, nppMachinery, nameModel, handlerModel, portMachinery, directoryGroupCashRegister, startDateGroupCashRegister));
                    }

                    List<CashRegisterItemInfo> cashRegisterItemInfoList = new ArrayList<CashRegisterItemInfo>();
                    for (ImMap<Object, ObjectValue> row : skuResult.valueIt()) {
                        String barcode = trim((String) row.get("idBarcode").getValue());
                        String name = trim((String) row.get("nameMachineryPriceTransactionBarcode").getValue());
                        BigDecimal price = (BigDecimal) row.get("priceMachineryPriceTransactionBarcode").getValue();
                        Boolean isWeight = row.get("isWeightMachineryPriceTransactionBarcode").getValue() != null;
                        String composition = scalesItemLM == null ? null : (String) row.get("compositionMachineryPriceTransactionBarcode").getValue();

                        List<String> hierarchyItemGroup = new ArrayList<String>();
                        String canonicalNameSkuGroup = null;
                        if (itemLM != null) {
                            ObjectValue skuGroupObject = row.get("skuGroupMachineryPriceTransactionBarcode");
                            if (skuGroupObject instanceof DataObject) {
                                String idItemGroup = (String) itemLM.findLCPByCompoundOldName("idItemGroup").read(session, skuGroupObject);
                                hierarchyItemGroup.add(idItemGroup);
                                ObjectValue parentSkuGroup;
                                while ((parentSkuGroup = equLM.findLCPByCompoundOldName("parentSkuGroup").readClasses(session, (DataObject) skuGroupObject)) instanceof DataObject) {
                                    hierarchyItemGroup.add((String) itemLM.findLCPByCompoundOldName("idItemGroup").read(session, parentSkuGroup));
                                    skuGroupObject = parentSkuGroup;
                                }
                                canonicalNameSkuGroup = idItemGroup == null ? "" : trim((String) equLM.findLCPByCompoundOldName("canonicalNameSkuGroup").read(session, itemLM.findLCPByCompoundOldName("itemGroupId").readClasses(session, new DataObject(idItemGroup))));
                            }
                        }
                        
                        cashRegisterItemInfoList.add(new CashRegisterItemInfo(barcode, name, price, isWeight, 
                                composition, canonicalNameSkuGroup, hierarchyItemGroup));
                    }
                    
                    transactionList.add(new TransactionCashRegisterInfo((Integer) transactionObject.getValue(),
                            dateTimeCode, date, cashRegisterItemInfoList, cashRegisterInfoList, nppGroupMachinery));

                } else if (scalesLM != null && transactionObject.objectClass.equals(scalesLM.findClassByCompoundName("ScalesPriceTransaction"))) {
                    List<ScalesInfo> scalesInfoList = new ArrayList<ScalesInfo>();
                    String directory = (String) scalesLM.findLCPByCompoundOldName("directoryGroupScales").read(session, groupMachineryObject);
                    String pieceCodeGroupScales = (String) scalesLM.findLCPByCompoundOldName("pieceCodeGroupScales").read(session, groupMachineryObject);
                    String weightCodeGroupScales = (String) scalesLM.findLCPByCompoundOldName("weightCodeGroupScales").read(session, groupMachineryObject);

                    LCP<PropertyInterface> isScales = (LCP<PropertyInterface>) scalesLM.is(scalesLM.findClassByCompoundName("Scales"));

                    ImRevMap<PropertyInterface, KeyExpr> scalesKeys = isScales.getMapKeys();
                    KeyExpr scalesKey = scalesKeys.singleValue();
                    QueryBuilder<PropertyInterface, Object> scalesQuery = new QueryBuilder<PropertyInterface, Object>(scalesKeys);

                    String[] scalesProperties = new String[]{"portMachinery", "nppMachinery", "nameCheckModelCheck", "handlerModelMachinery"};
                    for (String property : scalesProperties) {
                        scalesQuery.addProperty(property, scalesLM.findLCPByCompoundOldName(property).getExpr(scalesKey));
                    }
                    scalesQuery.and(isScales.property.getExpr(scalesKeys).getWhere());
                    scalesQuery.and(scalesLM.findLCPByCompoundOldName("groupScalesScales").getExpr(scalesKey).compare(groupMachineryObject, Compare.EQUALS));

                    ImOrderMap<ImMap<PropertyInterface, Object>, ImMap<Object, Object>> scalesResult = scalesQuery.execute(session);

                    for (ImMap<Object, Object> values : scalesResult.valueIt()) {
                        String portMachinery = (String) values.get("portMachinery");
                        Integer nppMachinery = (Integer) values.get("nppMachinery");
                        String nameModel = (String) values.get("nameModelMachinery");
                        String handlerModel = (String) values.get("handlerModelMachinery");
                        scalesInfoList.add(new ScalesInfo(nppMachinery, nameModel, handlerModel, portMachinery, directory,
                                pieceCodeGroupScales, weightCodeGroupScales));
                    }

                    List<ScalesItemInfo> scalesItemInfoList = new ArrayList<ScalesItemInfo>();
                    for (ImMap<Object, ObjectValue> row : skuResult.valueIt()) {
                        String barcode = trim((String) row.get("idBarcode").getValue());
                        String name = trim((String) row.get("nameMachineryPriceTransactionBarcode").getValue());
                        BigDecimal price = (BigDecimal) row.get("priceMachineryPriceTransactionBarcode").getValue();
                        Date expiryDate = (Date) row.get("expiryDateMachineryPriceTransactionBarcode").getValue();
                        Boolean isWeight = row.get("isWeightMachineryPriceTransactionBarcode").getValue() != null;
                        BigDecimal daysExpiry = (BigDecimal) row.get("daysExpiryMachineryPriceTransactionBarcode").getValue();
                        Integer hoursExpiry = (Integer) row.get("hoursExpiryMachineryPriceTransactionBarcode").getValue();
                        Integer labelFormat = (Integer) row.get("labelFormatMachineryPriceTransactionBarcode").getValue();
                        String composition = (String) row.get("compositionMachineryPriceTransactionBarcode").getValue();

                        List<String> hierarchyItemGroup = new ArrayList<String>();
                        if (itemLM != null) {
                            ObjectValue skuGroupObject = row.get("skuGroupMachineryPriceTransactionBarcode");
                            if (skuGroupObject instanceof DataObject) {
                                String idItemGroup = (String) itemLM.findLCPByCompoundOldName("idItemGroup").read(session, skuGroupObject);
                                hierarchyItemGroup.add(idItemGroup);
                                ObjectValue parentSkuGroup;
                                while ((parentSkuGroup = equLM.findLCPByCompoundOldName("parentSkuGroup").readClasses(session, (DataObject) skuGroupObject)) instanceof DataObject) {
                                    hierarchyItemGroup.add((String) itemLM.findLCPByCompoundOldName("idItemGroup").read(session, parentSkuGroup));
                                    skuGroupObject = parentSkuGroup;
                                }
                            }
                        }
                        Integer cellScalesObject = composition == null ? null : (Integer) equLM.findLCPByCompoundOldName("cellScalesGroupScalesComposition").read(session, groupMachineryObject, new DataObject(composition, StringClass.text));
                        Integer compositionNumberCellScales = cellScalesObject == null ? null : (Integer) equLM.findLCPByCompoundOldName("numberCellScales").read(session, new DataObject(cellScalesObject, (ConcreteClass) equLM.findClassByCompoundName("CellScales")));

                        scalesItemInfoList.add(new ScalesItemInfo(barcode, name, price, isWeight, 
                                daysExpiry, hoursExpiry, expiryDate, labelFormat, composition, compositionNumberCellScales, hierarchyItemGroup));
                    }

                    transactionList.add(new TransactionScalesInfo((Integer) transactionObject.getValue(),
                            dateTimeCode, scalesItemInfoList, scalesInfoList, snapshotTransaction));

                } else if (priceCheckerLM != null && transactionObject.objectClass.equals(priceCheckerLM.findClassByCompoundName("PriceCheckerPriceTransaction"))) {
                    List<PriceCheckerInfo> priceCheckerInfoList = new ArrayList<PriceCheckerInfo>();
                    LCP<PropertyInterface> isCheck = (LCP<PropertyInterface>) priceCheckerLM.is(priceCheckerLM.findClassByCompoundName("Check"));

                    ImRevMap<PropertyInterface, KeyExpr> checkKeys = isCheck.getMapKeys();
                    KeyExpr checkKey = checkKeys.singleValue();
                    QueryBuilder<PropertyInterface, Object> checkQuery = new QueryBuilder<PropertyInterface, Object>(checkKeys);

                    String[] checkProperties = new String[]{"portMachinery", "nppMachinery", "nameCheckModelCheck"};
                    for (String property : checkProperties) {
                        checkQuery.addProperty(property, priceCheckerLM.findLCPByCompoundOldName(property).getExpr(checkKey));
                    }
                    checkQuery.and(isCheck.property.getExpr(checkKeys).getWhere());
                    checkQuery.and(priceCheckerLM.findLCPByCompoundOldName("groupPriceCheckerPriceChecker").getExpr(checkKey).compare(groupMachineryObject, Compare.EQUALS));

                    ImOrderMap<ImMap<PropertyInterface, Object>, ImMap<Object, Object>> checkResult = checkQuery.execute(session);

                    for (ImMap<Object, Object> values : checkResult.valueIt()) {
                        priceCheckerInfoList.add(new PriceCheckerInfo((Integer) values.get("nppMachinery"), (String) values.get("nameCheckModelCheck"),
                                null, (String) values.get("portMachinery")));
                    }

                    List<PriceCheckerItemInfo> priceCheckerItemInfoList = new ArrayList<PriceCheckerItemInfo>();
                    for (ImMap<Object, ObjectValue> row : skuResult.valueIt()) {
                        String barcode = trim((String) row.get("idBarcode").getValue());
                        String name = trim((String) row.get("nameMachineryPriceTransactionBarcode").getValue());
                        BigDecimal price = (BigDecimal) row.get("priceMachineryPriceTransactionBarcode").getValue();
                        Boolean isWeight = row.get("isWeightMachineryPriceTransactionBarcode").getValue() != null;              
                        priceCheckerItemInfoList.add(new PriceCheckerItemInfo(barcode, name, price, isWeight));
                    }
                    
                    transactionList.add(new TransactionPriceCheckerInfo((Integer) transactionObject.getValue(),
                            dateTimeCode, priceCheckerItemInfoList, priceCheckerInfoList));


                } else if (terminalLM != null && transactionObject.objectClass.equals(terminalLM.findClassByCompoundName("TerminalPriceTransaction"))) {
                    List<TerminalInfo> terminalInfoList = new ArrayList<TerminalInfo>();

                    Integer nppGroupTerminal = (Integer) terminalLM.findLCPByCompoundOldName("nppGroupMachinery").read(session, groupMachineryObject);
                    String directoryGroupTerminal = (String) terminalLM.findLCPByCompoundOldName("directoryGroupTerminal").read(session, groupMachineryObject);
                    
                    LCP<PropertyInterface> isTerminal = (LCP<PropertyInterface>) terminalLM.is(terminalLM.findClassByCompoundName("Terminal"));

                    ImRevMap<PropertyInterface, KeyExpr> terminalKeys = isTerminal.getMapKeys();
                    KeyExpr terminalKey = terminalKeys.singleValue();
                    QueryBuilder<PropertyInterface, Object> terminalQuery = new QueryBuilder<PropertyInterface, Object>(terminalKeys);

                    String[] terminalProperties = new String[]{"portMachinery", "nppMachinery", "nameModelMachinery", "handlerModelMachinery"};
                    for (String property : terminalProperties) {
                        terminalQuery.addProperty(property, terminalLM.findLCPByCompoundOldName(property).getExpr(terminalKey));
                    }
                    terminalQuery.and(isTerminal.property.getExpr(terminalKeys).getWhere());
                    terminalQuery.and(terminalLM.findLCPByCompoundOldName("groupTerminalTerminal").getExpr(terminalKey).compare(groupMachineryObject, Compare.EQUALS));

                    ImOrderMap<ImMap<PropertyInterface, Object>, ImMap<Object, Object>> terminalResult = terminalQuery.execute(session);

                    for (ImMap<Object, Object> values : terminalResult.valueIt()) {
                        terminalInfoList.add(new TerminalInfo((Integer) values.get("nppMachinery"),
                                (String) values.get("nameModelMachinery"), (String) values.get("handlerModelMachinery"),
                                (String) values.get("portMachinery"), directoryGroupTerminal));
                    }

                    List<TerminalItemInfo> terminalItemInfoList = new ArrayList<TerminalItemInfo>();
                    for (ImMap<Object, ObjectValue> row : skuResult.valueIt()) {
                        String barcode = trim((String) row.get("idBarcode").getValue());
                        String name = trim((String) row.get("nameMachineryPriceTransactionBarcode").getValue());
                        BigDecimal price = (BigDecimal) row.get("priceMachineryPriceTransactionBarcode").getValue();
                        Boolean isWeight = row.get("isWeightMachineryPriceTransactionBarcode").getValue() != null;
                        
                        terminalItemInfoList.add(new TerminalItemInfo(barcode, name, price, isWeight, null/*quantity*/, null/*image*/));
                    }

                    List<TerminalHandbookType> terminalHandbookTypeList = readTerminalHandbookTypeList(session);
                    List<TerminalDocumentType> terminalDocumentTypeList = readTerminalDocumentTypeList(session);                   
                    List<TerminalOrder> terminalOrderList = readTerminalOrderList(session);

                    transactionList.add(new TransactionTerminalInfo((Integer) transactionObject.getValue(),
                            dateTimeCode, terminalItemInfoList, terminalInfoList, terminalHandbookTypeList,
                            terminalDocumentTypeList, terminalOrderList, nppGroupTerminal, directoryGroupTerminal, snapshotTransaction));
                }
            }
            return transactionList;
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw Throwables.propagate(e);
        } catch (SQLHandledException e) {
            throw Throwables.propagate(e);
        }
    }
    
    private List<TerminalHandbookType> readTerminalHandbookTypeList(DataSession session) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        List<TerminalHandbookType> terminalHandbookTypeList = new ArrayList<TerminalHandbookType>();
        KeyExpr terminalHandbookTypeExpr = new KeyExpr("terminalHandbookType");
        ImRevMap<Object, KeyExpr> keys = MapFact.singletonRev((Object) "terminalHandbookType", terminalHandbookTypeExpr);
        QueryBuilder<Object, Object> query = new QueryBuilder<Object, Object>(keys);
        String[] properties = new String[]{"idTerminalHandbookType", "staticCaption"};
        for (String property : properties) {
            query.addProperty(property, terminalLM.findLCPByCompoundOldName(property).getExpr(terminalHandbookTypeExpr));
        }
        query.and(terminalLM.findLCPByCompoundOldName("idTerminalHandbookType").getExpr(terminalHandbookTypeExpr).getWhere());
        ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);
        for(ImMap<Object, Object> entry : result.values()) {
            String id = trim((String) entry.get("idTerminalHandbookType"));
            String name = trim((String) entry.get("staticCaption"));
            terminalHandbookTypeList.add(new TerminalHandbookType(id, name));
        }
        return terminalHandbookTypeList;
    }
    
    private List<TerminalDocumentType> readTerminalDocumentTypeList(DataSession session) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        List<TerminalDocumentType> terminalDocumentTypeList = new ArrayList<TerminalDocumentType>();
        KeyExpr terminalDocumentTypeExpr = new KeyExpr("terminalDocumentType");
        ImRevMap<Object, KeyExpr> keys = MapFact.singletonRev((Object) "terminalDocumentType", terminalDocumentTypeExpr);
        QueryBuilder<Object, Object> query = new QueryBuilder<Object, Object>(keys);
        String[] properties = new String[]{"idTerminalDocumentType", "nameTerminalDocumentType", "idTerminalHandbookType1TerminalDocumentType",
                "idTerminalHandbookType2TerminalDocumentType"};
        for (String property : properties) {
            query.addProperty(property, terminalLM.findLCPByCompoundOldName(property).getExpr(terminalDocumentTypeExpr));
        }
        query.and(terminalLM.findLCPByCompoundOldName("idTerminalDocumentType").getExpr(terminalDocumentTypeExpr).getWhere());
        ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);
        for(ImMap<Object, Object> entry : result.values()) {
            String id = trim((String) entry.get("idTerminalDocumentType"));
            String name = trim((String) entry.get("nameTerminalDocumentType"));
            String analytics1 = trim((String) entry.get("idTerminalHandbookType1TerminalDocumentType"));
            String analytics2 = trim((String) entry.get("idTerminalHandbookType2TerminalDocumentType"));
            terminalDocumentTypeList.add(new TerminalDocumentType(id, name, analytics1, analytics2));
        }
        return terminalDocumentTypeList;
    }

    private List<TerminalOrder> readTerminalOrderList(DataSession session) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {

        if (purchaseOrderLM != null) {

            List<TerminalOrder> terminalOrderList = new ArrayList<TerminalOrder>();
            KeyExpr orderExpr = new KeyExpr("order");
            KeyExpr orderDetailExpr = new KeyExpr("orderDetail");
            ImRevMap<Object, KeyExpr> orderKeys = MapFact.toRevMap((Object) "Order", orderExpr, "OrderDetail", orderDetailExpr);
            QueryBuilder<Object, Object> orderQuery = new QueryBuilder<Object, Object>(orderKeys);
            String[] orderProperties = new String[]{"dateOrder", "numberOrder", "supplierOrder"};
            for (String property : orderProperties) {
                orderQuery.addProperty(property, purchaseOrderLM.findLCPByCompoundOldName(property).getExpr(orderExpr));
            }
            String[] orderDetailProperties = new String[]{"idBarcodeSkuOrderDetail", "priceOrderDetail", "quantityOrderDetail"};
            for (String property : orderDetailProperties) {
                orderQuery.addProperty(property, purchaseOrderLM.findLCPByCompoundOldName(property).getExpr(orderDetailExpr));
            }
            orderQuery.and(purchaseOrderLM.findLCPByCompoundOldName("Purchase.orderOrderDetail").getExpr(orderDetailExpr).compare(orderExpr, Compare.EQUALS));
            orderQuery.and(purchaseOrderLM.findLCPByCompoundOldName("numberOrder").getExpr(orderExpr).getWhere());
            orderQuery.and(purchaseOrderLM.findLCPByCompoundOldName("idBarcodeSkuOrderDetail").getExpr(orderDetailExpr).getWhere());
            ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> orderResult = orderQuery.executeClasses(session);
            for (ImMap<Object, ObjectValue> entry : orderResult.values()) {
                Date dateOrder = (Date) entry.get("dateOrder").getValue();
                String numberOrder = trim((String) entry.get("numberOrder").getValue());
                String idSupplier = trim((String) purchaseOrderLM.findLCPByCompoundOldName("idLegalEntity").read(session, entry.get("supplierOrder")));
                String barcode = trim((String) entry.get("idBarcodeSkuOrderDetail").getValue());
                BigDecimal price = (BigDecimal) entry.get("priceOrderDetail").getValue();
                BigDecimal quantity = (BigDecimal) entry.get("quantityOrderDetail").getValue();
                terminalOrderList.add(new TerminalOrder(dateOrder, numberOrder, idSupplier, barcode, price, quantity));
            }
            return terminalOrderList;
        } else return null;
    }

    @Override
    public Map<Date, Set<String>> readRequestSalesInfo(String equServerID) throws RemoteException, SQLException {

        Map<Date, Set<String>> directoriesMap = new HashMap<Date, Set<String>>();

        if (cashRegisterLM != null) {
            try {
                logger.info("RequestSalesInfoStock started");

                DataSession session = getDbManager().createSession();

                KeyExpr groupMachineryExpr = new KeyExpr("groupMachinery");
                KeyExpr cashRegisterExpr = new KeyExpr("cashRegister");
                ImRevMap<Object, KeyExpr> keys = MapFact.toRevMap((Object) "GroupMachinery", groupMachineryExpr, "cashRegister", cashRegisterExpr);
                QueryBuilder<Object, Object> query = new QueryBuilder<Object, Object>(keys);

                query.addProperty("stockGroupMachinery", equLM.findLCPByCompoundOldName("stockGroupMachinery").getExpr(groupMachineryExpr));
                query.addProperty("directoryGroupCashRegister", cashRegisterLM.findLCPByCompoundOldName("directoryGroupCashRegister").getExpr(groupMachineryExpr));
                query.and(equLM.findLCPByCompoundOldName("sidEquipmentServerGroupMachinery").getExpr(groupMachineryExpr).compare(new DataObject(equServerID, StringClass.get(20)), Compare.EQUALS));
                query.and(cashRegisterLM.findLCPByCompoundOldName("groupCashRegisterCashRegister").getExpr(cashRegisterExpr).compare(groupMachineryExpr, Compare.EQUALS));
                query.and(cashRegisterLM.findLCPByCompoundOldName("directoryGroupCashRegister").getExpr(groupMachineryExpr).getWhere());

                ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> result = query.executeClasses(session);
                for (int i = 0, size = result.size(); i < size; i++) {

                    DataObject departmentStoreObject = (DataObject) result.getValue(i).get("stockGroupMachinery");
                    String directoryGroupCashRegister = (String) result.getValue(i).get("directoryGroupCashRegister").getValue();
                    boolean requestSalesInfoStock = equLM.findLCPByCompoundOldName("requestSalesInfoStock").read(session, departmentStoreObject) != null;
                    Date dateRequestSalesInfoStock = (Date) equLM.findLCPByCompoundOldName("dateRequestSalesInfoStock").read(session, departmentStoreObject);

                    String nameDepartmentStore = (String) cashRegisterLM.findLCPByCompoundOldName("nameDepartmentStore").read(session, departmentStoreObject);
                    logger.info("RequestSalesInfoStock: " + nameDepartmentStore + ": " + requestSalesInfoStock);

                    if (requestSalesInfoStock) {
                        equLM.findLCPByCompoundOldName("requestSalesInfoStock").change((Object) null, session, departmentStoreObject);
                        Set<String> directories = directoriesMap.containsKey(dateRequestSalesInfoStock) ? directoriesMap.get(dateRequestSalesInfoStock) : new HashSet<String>();
                        directories.add(directoryGroupCashRegister);
                        directoriesMap.put(dateRequestSalesInfoStock, directories);
                    }
                }
                session.apply(getBusinessLogics());
            } catch (ScriptingErrorLog.SemanticErrorException e) {
                throw Throwables.propagate(e);
            } catch (SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
        return directoriesMap;
    }

    @Override
    public List<CashRegisterInfo> readCashRegisterInfo(String equServerID) throws RemoteException, SQLException {
        try {

            List<CashRegisterInfo> cashRegisterInfoList = new ArrayList<CashRegisterInfo>();

            if (cashRegisterLM != null) {

                DataSession session = getDbManager().createSession();

                KeyExpr groupCashRegisterExpr = new KeyExpr("groupCashRegister");
                KeyExpr cashRegisterExpr = new KeyExpr("cashRegister");

                ImRevMap<Object, KeyExpr> keys = MapFact.toRevMap((Object) "GroupCashRegister", groupCashRegisterExpr, "cashRegister", cashRegisterExpr);
                QueryBuilder<Object, Object> query = new QueryBuilder<Object, Object>(keys);

                query.addProperty("nppMachinery", cashRegisterLM.findLCPByCompoundOldName("nppMachinery").getExpr(cashRegisterExpr));
                query.addProperty("nameModelMachinery", cashRegisterLM.findLCPByCompoundOldName("nameModelMachinery").getExpr(cashRegisterExpr));
                query.addProperty("handlerModelMachinery", cashRegisterLM.findLCPByCompoundOldName("handlerModelMachinery").getExpr(cashRegisterExpr));
                query.addProperty("portMachinery", cashRegisterLM.findLCPByCompoundOldName("portMachinery").getExpr(cashRegisterExpr));
                query.addProperty("directoryGroupCashRegister", cashRegisterLM.findLCPByCompoundOldName("directoryGroupCashRegister").getExpr(groupCashRegisterExpr));
                query.addProperty("startDateGroupCashRegister", cashRegisterLM.findLCPByCompoundOldName("startDateGroupCashRegister").getExpr(groupCashRegisterExpr));
                query.addProperty("nppGroupMachinery", cashRegisterLM.findLCPByCompoundOldName("nppGroupMachinery").getExpr(groupCashRegisterExpr));

                query.and(cashRegisterLM.findLCPByCompoundOldName("handlerModelMachinery").getExpr(cashRegisterExpr).getWhere());
                query.and(cashRegisterLM.findLCPByCompoundOldName("directoryGroupCashRegister").getExpr(groupCashRegisterExpr).getWhere());
                query.and(cashRegisterLM.findLCPByCompoundOldName("groupCashRegisterCashRegister").getExpr(cashRegisterExpr).compare(groupCashRegisterExpr, Compare.EQUALS));
                query.and(equLM.findLCPByCompoundOldName("sidEquipmentServerGroupMachinery").getExpr(groupCashRegisterExpr).compare(new DataObject(equServerID, StringClass.get(20)), Compare.EQUALS));

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);

                for (ImMap<Object, Object> row : result.values()) {
                    cashRegisterInfoList.add(new CashRegisterInfo((Integer) row.get("nppGroupMachinery"), (Integer) row.get("nppMachinery"),
                            (String) row.get("nameModelMachinery"), (String) row.get("handlerModelMachinery"), (String) row.get("portMachinery"),
                            (String) row.get("directoryGroupCashRegister"), (java.sql.Date) row.get("startDateGroupCashRegister")));
                }
            }
            return cashRegisterInfoList;
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw new RuntimeException(e.toString());
        } catch (SQLHandledException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<TerminalInfo> readTerminalInfo(String equServerID) throws RemoteException, SQLException {
        try {

            List<TerminalInfo> terminalInfoList = new ArrayList<TerminalInfo>();

            if (terminalLM != null) {

                DataSession session = getDbManager().createSession();

                KeyExpr groupTerminalExpr = new KeyExpr("groupTerminal");
                KeyExpr terminalExpr = new KeyExpr("terminal");

                ImRevMap<Object, KeyExpr> keys = MapFact.toRevMap((Object) "GroupTerminal", groupTerminalExpr, "terminal", terminalExpr);
                QueryBuilder<Object, Object> query = new QueryBuilder<Object, Object>(keys);

                query.addProperty("nppMachinery", terminalLM.findLCPByCompoundOldName("nppMachinery").getExpr(terminalExpr));
                query.addProperty("nameModelMachinery", terminalLM.findLCPByCompoundOldName("nameModelMachinery").getExpr(terminalExpr));
                query.addProperty("handlerModelMachinery", terminalLM.findLCPByCompoundOldName("handlerModelMachinery").getExpr(terminalExpr));
                query.addProperty("portMachinery", terminalLM.findLCPByCompoundOldName("portMachinery").getExpr(terminalExpr));
                query.addProperty("directoryGroupTerminal", terminalLM.findLCPByCompoundOldName("directoryGroupTerminal").getExpr(groupTerminalExpr));

                query.and(terminalLM.findLCPByCompoundOldName("handlerModelMachinery").getExpr(terminalExpr).getWhere());
                query.and(terminalLM.findLCPByCompoundOldName("directoryGroupTerminal").getExpr(groupTerminalExpr).getWhere());
                query.and(terminalLM.findLCPByCompoundOldName("groupTerminalTerminal").getExpr(terminalExpr).compare(groupTerminalExpr, Compare.EQUALS));
                query.and(equLM.findLCPByCompoundOldName("sidEquipmentServerGroupMachinery").getExpr(groupTerminalExpr).compare(new DataObject(equServerID, StringClass.get(20)), Compare.EQUALS));

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);

                for (ImMap<Object, Object> row : result.values()) {
                    terminalInfoList.add(new TerminalInfo((Integer) row.get("nppMachinery"), (String) row.get("nameModelMachinery"), 
                            (String) row.get("handlerModelMachinery"), (String) row.get("portMachinery"), (String) row.get("directoryGroupTerminal")));
                }
            }
            return terminalInfoList;
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw new RuntimeException(e.toString());
        } catch (SQLHandledException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public String sendTerminalInfo(List<TerminalDocumentDetail> terminalDocumentDetailList, String equServerID) throws RemoteException, SQLException {

        try {

            List<ImportProperty<?>> props = new ArrayList<ImportProperty<?>>();
            List<ImportField> fields = new ArrayList<ImportField>();
            List<ImportKey<?>> keys = new ArrayList<ImportKey<?>>();

            List<List<Object>> data = initData(terminalDocumentDetailList.size());

            ImportField idTerminalDocumentField = new ImportField(terminalLM.findLCPByCompoundOldName("idTerminalDocument"));
            ImportKey<?> terminalDocumentKey = new ImportKey((ConcreteCustomClass) terminalLM.findClassByCompoundName("TerminalDocument"),
                    terminalLM.findLCPByCompoundOldName("terminalDocumentId").getMapping(idTerminalDocumentField));
            keys.add(terminalDocumentKey);
            props.add(new ImportProperty(idTerminalDocumentField, terminalLM.findLCPByCompoundOldName("idTerminalDocument").getMapping(terminalDocumentKey)));
            props.add(new ImportProperty(idTerminalDocumentField, terminalLM.findLCPByCompoundOldName("titleTerminalDocument").getMapping(terminalDocumentKey)));
            fields.add(idTerminalDocumentField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).numberTerminalDocument);

            ImportField idTerminalHandbookType1TerminalDocumentField = new ImportField(terminalLM.findLCPByCompoundOldName("idTerminalHandbookType1TerminalDocument"));
            props.add(new ImportProperty(idTerminalHandbookType1TerminalDocumentField, terminalLM.findLCPByCompoundOldName("idTerminalHandbookType1TerminalDocument").getMapping(terminalDocumentKey)));
            fields.add(idTerminalHandbookType1TerminalDocumentField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).idTerminalHandbookType1);

            ImportField idTerminalHandbookType2TerminalDocumentField = new ImportField(terminalLM.findLCPByCompoundOldName("idTerminalHandbookType2TerminalDocument"));
            props.add(new ImportProperty(idTerminalHandbookType2TerminalDocumentField, terminalLM.findLCPByCompoundOldName("idTerminalHandbookType2TerminalDocument").getMapping(terminalDocumentKey)));
            fields.add(idTerminalHandbookType2TerminalDocumentField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).idTerminalHandbookType2);

            ImportField idTerminalDocumentTypeField = new ImportField(terminalLM.findLCPByCompoundOldName("idTerminalDocumentType"));
            ImportKey<?> terminalDocumentTypeKey = new ImportKey((ConcreteCustomClass) terminalLM.findClassByCompoundName("TerminalDocumentType"),
                    terminalLM.findLCPByCompoundOldName("terminalDocumentTypeId").getMapping(idTerminalDocumentTypeField));
            keys.add(terminalDocumentTypeKey);
            props.add(new ImportProperty(idTerminalDocumentTypeField, terminalLM.findLCPByCompoundOldName("idTerminalDocumentType").getMapping(terminalDocumentTypeKey)));
            props.add(new ImportProperty(idTerminalDocumentTypeField, terminalLM.findLCPByCompoundOldName("terminalDocumentTypeTerminalDocument").getMapping(terminalDocumentKey),
                    terminalLM.object(terminalLM.findClassByCompoundName("TerminalDocumentType")).getMapping(terminalDocumentTypeKey)));
            fields.add(idTerminalDocumentTypeField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).idTerminalDocumentType);
            
            ImportField idTerminalDocumentDetailField = new ImportField(terminalLM.findLCPByCompoundOldName("idTerminalDocumentDetail"));
            ImportKey<?> terminalDocumentDetailKey = new ImportKey((ConcreteCustomClass) terminalLM.findClassByCompoundName("TerminalDocumentDetail"),
                    terminalLM.findLCPByCompoundOldName("terminalDocumentDetailId").getMapping(idTerminalDocumentDetailField));
            keys.add(terminalDocumentDetailKey);
            props.add(new ImportProperty(idTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("idTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            props.add(new ImportProperty(idTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("nameTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            props.add(new ImportProperty(idTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("numberTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            props.add(new ImportProperty(idTerminalDocumentField, terminalLM.findLCPByCompoundOldName("terminalDocumentTerminalDocumentDetail").getMapping(terminalDocumentDetailKey),
                    terminalLM.object(terminalLM.findClassByCompoundName("TerminalDocument")).getMapping(terminalDocumentKey)));
            fields.add(idTerminalDocumentDetailField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).idTerminalDocumentDetail);

            ImportField barcodeTerminalDocumentDetailField = new ImportField(terminalLM.findLCPByCompoundOldName("barcodeTerminalDocumentDetail"));
            props.add(new ImportProperty(barcodeTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("barcodeTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            fields.add(barcodeTerminalDocumentDetailField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).barcodeTerminalDocumentDetail);

            ImportField priceTerminalDocumentDetailField = new ImportField(terminalLM.findLCPByCompoundOldName("priceTerminalDocumentDetail"));
            props.add(new ImportProperty(priceTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("priceTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            fields.add(priceTerminalDocumentDetailField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).priceTerminalDocumentDetail);

            ImportField quantityTerminalDocumentDetailField = new ImportField(terminalLM.findLCPByCompoundOldName("quantityTerminalDocumentDetail"));
            props.add(new ImportProperty(quantityTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("quantityTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            fields.add(quantityTerminalDocumentDetailField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).quantityTerminalDocumentDetail);

            ImportField sumTerminalDocumentDetailField = new ImportField(terminalLM.findLCPByCompoundOldName("sumTerminalDocumentDetail"));
            props.add(new ImportProperty(sumTerminalDocumentDetailField, terminalLM.findLCPByCompoundOldName("sumTerminalDocumentDetail").getMapping(terminalDocumentDetailKey)));
            fields.add(sumTerminalDocumentDetailField);
            for (int i = 0; i < terminalDocumentDetailList.size(); i++)
                data.get(i).add(terminalDocumentDetailList.get(i).sumTerminalDocumentDetail);
            

            ImportTable table = new ImportTable(fields, data);

            DataSession session = getDbManager().createSession();
            session.pushVolatileStats();
            IntegrationService service = new IntegrationService(session, table, keys, props);
            service.synchronize(true, false);
            String result = session.applyMessage(getBusinessLogics());
            session.popVolatileStats();
            session.close(); 
            
            return result;

        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public String sendSalesInfo(List<SalesInfo> salesInfoList, String equipmentServer, Integer numberAtATime) throws IOException, SQLException {
        try {

            if (zReportLM != null && salesInfoList != null && !salesInfoList.isEmpty()) {

                if (numberAtATime == null)
                    numberAtATime = salesInfoList.size();

                for (int start = 0; true;) {

                    int finish = (start + numberAtATime) < salesInfoList.size() ? (start + numberAtATime) : salesInfoList.size();
                    
                    Integer lastNumberReceipt = start < finish ? salesInfoList.get(finish - 1).numberReceipt : null;
                    if(lastNumberReceipt != null) {
                        while(start < finish && salesInfoList.size() > finish && salesInfoList.get(finish).numberReceipt.equals(lastNumberReceipt))
                            finish++;                        
                    }
                    
                    List<SalesInfo> data = start < finish ? salesInfoList.subList(start, finish) : new ArrayList<SalesInfo>();
                    start = finish;
                    if (data.isEmpty())
                        return null;

                    logger.info(String.format("Kristal: Sending SalesInfo from %s to %s", start, finish));
                    
                    DataSession session = getDbManager().createSession();
                    ImportField nppGroupMachineryField = new ImportField(zReportLM.findLCPByCompoundOldName("nppGroupMachinery"));
                    ImportField nppMachineryField = new ImportField(zReportLM.findLCPByCompoundOldName("nppMachinery"));

                    ImportField idZReportField = new ImportField(zReportLM.findLCPByCompoundOldName("idZReport"));
                    ImportField numberZReportField = new ImportField(zReportLM.findLCPByCompoundOldName("numberZReport"));

                    ImportField idReceiptField = new ImportField(zReportLM.findLCPByCompoundOldName("idReceipt"));
                    ImportField numberReceiptField = new ImportField(zReportLM.findLCPByCompoundOldName("numberReceipt"));
                    ImportField dateReceiptField = new ImportField(zReportLM.findLCPByCompoundOldName("dateReceipt"));
                    ImportField timeReceiptField = new ImportField(zReportLM.findLCPByCompoundOldName("timeReceipt"));
                    ImportField isPostedZReportField = new ImportField(zReportLM.findLCPByCompoundOldName("isPostedZReport"));

                    ImportField idReceiptDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("idReceiptDetail"));
                    ImportField numberReceiptDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("numberReceiptDetail"));
                    ImportField idBarcodeReceiptDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("idBarcodeReceiptDetail"));

                    ImportField quantityReceiptSaleDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("quantityReceiptSaleDetail"));
                    ImportField priceReceiptSaleDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("priceReceiptSaleDetail"));
                    ImportField sumReceiptSaleDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("sumReceiptSaleDetail"));
                    ImportField discountSumReceiptSaleDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("discountSumReceiptSaleDetail"));
                    ImportField discountSumSaleReceiptField = new ImportField(zReportLM.findLCPByCompoundOldName("discountSumSaleReceipt"));

                    ImportField quantityReceiptReturnDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("quantityReceiptReturnDetail"));
                    ImportField priceReceiptReturnDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("priceReceiptReturnDetail"));
                    ImportField retailSumReceiptReturnDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("sumReceiptReturnDetail"));
                    ImportField discountSumReceiptReturnDetailField = new ImportField(zReportLM.findLCPByCompoundOldName("discountSumReceiptReturnDetail"));
                    ImportField discountSumReturnReceiptField = new ImportField(zReportLM.findLCPByCompoundOldName("discountSumReturnReceipt"));

                    ImportField idPaymentField = new ImportField(zReportLM.findLCPByCompoundOldName("ZReport.idPayment"));
                    ImportField sidTypePaymentField = new ImportField(zReportLM.findLCPByCompoundOldName("sidPaymentType"));
                    ImportField sumPaymentField = new ImportField(zReportLM.findLCPByCompoundOldName("ZReport.sumPayment"));
                    ImportField numberPaymentField = new ImportField(zReportLM.findLCPByCompoundOldName("ZReport.numberPayment"));

                    ImportField seriesNumberDiscountCardField = null;
                    if (discountCardLM != null)
                        seriesNumberDiscountCardField = new ImportField(discountCardLM.findLCPByCompoundOldName("seriesNumberDiscountCard"));

                    List<ImportProperty<?>> saleProperties = new ArrayList<ImportProperty<?>>();
                    List<ImportProperty<?>> returnProperties = new ArrayList<ImportProperty<?>>();
                    List<ImportProperty<?>> paymentProperties = new ArrayList<ImportProperty<?>>();

                    ImportKey<?> zReportKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("ZReport"), zReportLM.findLCPByCompoundOldName("zReportId").getMapping(idZReportField));
                    ImportKey<?> cashRegisterKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("CashRegister"), zReportLM.findLCPByCompoundOldName("cashRegisterNppGroupCashRegisterNpp").getMapping(nppGroupMachineryField, nppMachineryField));
                    ImportKey<?> receiptKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("Receipt"), zReportLM.findLCPByCompoundOldName("receiptId").getMapping(idReceiptField));
                    ImportKey<?> skuKey = new ImportKey((CustomClass) zReportLM.findClassByCompoundName("Sku"), zReportLM.findLCPByCompoundOldName("skuBarcodeIdDate").getMapping(idBarcodeReceiptDetailField, dateReceiptField));
                    ImportKey<?> discountCardKey = null;
                    if (discountCardLM != null)
                        discountCardKey = new ImportKey((ConcreteCustomClass) discountCardLM.findClassByCompoundName("DiscountCard"), discountCardLM.findLCPByCompoundOldName("discountCardSeriesNumber").getMapping(seriesNumberDiscountCardField, dateReceiptField));

                    saleProperties.add(new ImportProperty(idZReportField, zReportLM.findLCPByCompoundOldName("idZReport").getMapping(zReportKey)));
                    saleProperties.add(new ImportProperty(numberZReportField, zReportLM.findLCPByCompoundOldName("numberZReport").getMapping(zReportKey)));
                    saleProperties.add(new ImportProperty(nppMachineryField, zReportLM.findLCPByCompoundOldName("cashRegisterZReport").getMapping(zReportKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("CashRegister")).getMapping(cashRegisterKey)));
                    saleProperties.add(new ImportProperty(dateReceiptField, zReportLM.findLCPByCompoundOldName("dateZReport").getMapping(zReportKey)));
                    saleProperties.add(new ImportProperty(timeReceiptField, zReportLM.findLCPByCompoundOldName("timeZReport").getMapping(zReportKey)));
                    saleProperties.add(new ImportProperty(isPostedZReportField, zReportLM.findLCPByCompoundOldName("isPostedZReport").getMapping(zReportKey)));

                    saleProperties.add(new ImportProperty(idReceiptField, zReportLM.findLCPByCompoundOldName("idReceipt").getMapping(receiptKey)));
                    saleProperties.add(new ImportProperty(numberReceiptField, zReportLM.findLCPByCompoundOldName("numberReceipt").getMapping(receiptKey)));
                    saleProperties.add(new ImportProperty(dateReceiptField, zReportLM.findLCPByCompoundOldName("dateReceipt").getMapping(receiptKey)));
                    saleProperties.add(new ImportProperty(timeReceiptField, zReportLM.findLCPByCompoundOldName("timeReceipt").getMapping(receiptKey)));
                    saleProperties.add(new ImportProperty(discountSumSaleReceiptField, zReportLM.findLCPByCompoundOldName("discountSumSaleReceipt").getMapping(receiptKey)));
                    saleProperties.add(new ImportProperty(numberZReportField, zReportLM.findLCPByCompoundOldName("zReportReceipt").getMapping(receiptKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("ZReport")).getMapping(zReportKey)));
                    if (discountCardLM != null) {
                        saleProperties.add(new ImportProperty(seriesNumberDiscountCardField, discountCardLM.findLCPByCompoundOldName("seriesNumberDiscountCard").getMapping(discountCardKey)));
                        saleProperties.add(new ImportProperty(seriesNumberDiscountCardField, discountCardLM.findLCPByCompoundOldName("discountCardReceipt").getMapping(receiptKey),
                                discountCardLM.object(discountCardLM.findClassByCompoundName("DiscountCard")).getMapping(discountCardKey)));
                    }
                    ImportKey<?> receiptSaleDetailKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("ReceiptSaleDetail"), zReportLM.findLCPByCompoundOldName("receiptDetailId").getMapping(idReceiptDetailField));
                    saleProperties.add(new ImportProperty(idReceiptDetailField, zReportLM.findLCPByCompoundOldName("idReceiptDetail").getMapping(receiptSaleDetailKey)));
                    saleProperties.add(new ImportProperty(numberReceiptDetailField, zReportLM.findLCPByCompoundOldName("numberReceiptDetail").getMapping(receiptSaleDetailKey)));
                    saleProperties.add(new ImportProperty(idBarcodeReceiptDetailField, zReportLM.findLCPByCompoundOldName("idBarcodeReceiptDetail").getMapping(receiptSaleDetailKey)));
                    saleProperties.add(new ImportProperty(quantityReceiptSaleDetailField, zReportLM.findLCPByCompoundOldName("quantityReceiptSaleDetail").getMapping(receiptSaleDetailKey)));
                    saleProperties.add(new ImportProperty(priceReceiptSaleDetailField, zReportLM.findLCPByCompoundOldName("priceReceiptSaleDetail").getMapping(receiptSaleDetailKey)));
                    saleProperties.add(new ImportProperty(sumReceiptSaleDetailField, zReportLM.findLCPByCompoundOldName("sumReceiptSaleDetail").getMapping(receiptSaleDetailKey)));
                    if (discountCardLM != null) {
                        saleProperties.add(new ImportProperty(discountSumReceiptSaleDetailField, discountCardLM.findLCPByCompoundOldName("discountSumReceiptSaleDetail").getMapping(receiptSaleDetailKey)));
                    }
                    saleProperties.add(new ImportProperty(idReceiptField, zReportLM.findLCPByCompoundOldName("receiptReceiptDetail").getMapping(receiptSaleDetailKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("Receipt")).getMapping(receiptKey)));

                    saleProperties.add(new ImportProperty(idBarcodeReceiptDetailField, zReportLM.findLCPByCompoundOldName("skuReceiptSaleDetail").getMapping(receiptSaleDetailKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("Sku")).getMapping(skuKey)));

                    returnProperties.add(new ImportProperty(idZReportField, zReportLM.findLCPByCompoundOldName("idZReport").getMapping(zReportKey)));
                    returnProperties.add(new ImportProperty(numberZReportField, zReportLM.findLCPByCompoundOldName("numberZReport").getMapping(zReportKey)));
                    returnProperties.add(new ImportProperty(nppMachineryField, zReportLM.findLCPByCompoundOldName("cashRegisterZReport").getMapping(zReportKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("CashRegister")).getMapping(cashRegisterKey)));
                    returnProperties.add(new ImportProperty(dateReceiptField, zReportLM.findLCPByCompoundOldName("dateZReport").getMapping(zReportKey)));
                    returnProperties.add(new ImportProperty(timeReceiptField, zReportLM.findLCPByCompoundOldName("timeZReport").getMapping(zReportKey)));
                    returnProperties.add(new ImportProperty(isPostedZReportField, zReportLM.findLCPByCompoundOldName("isPostedZReport").getMapping(zReportKey)));

                    returnProperties.add(new ImportProperty(idReceiptField, zReportLM.findLCPByCompoundOldName("idReceipt").getMapping(receiptKey)));
                    returnProperties.add(new ImportProperty(numberReceiptField, zReportLM.findLCPByCompoundOldName("numberReceipt").getMapping(receiptKey)));
                    returnProperties.add(new ImportProperty(dateReceiptField, zReportLM.findLCPByCompoundOldName("dateReceipt").getMapping(receiptKey)));
                    returnProperties.add(new ImportProperty(timeReceiptField, zReportLM.findLCPByCompoundOldName("timeReceipt").getMapping(receiptKey)));
                    if (discountCardLM != null) {
                        returnProperties.add(new ImportProperty(discountSumReturnReceiptField, discountCardLM.findLCPByCompoundOldName("discountSumReturnReceipt").getMapping(receiptKey)));
                    }
                    returnProperties.add(new ImportProperty(numberZReportField, zReportLM.findLCPByCompoundOldName("zReportReceipt").getMapping(receiptKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("ZReport")).getMapping(zReportKey)));
                    if (discountCardLM != null) {
                        returnProperties.add(new ImportProperty(seriesNumberDiscountCardField, discountCardLM.findLCPByCompoundOldName("seriesNumberDiscountCard").getMapping(discountCardKey)));
                        returnProperties.add(new ImportProperty(seriesNumberDiscountCardField, discountCardLM.findLCPByCompoundOldName("discountCardReceipt").getMapping(receiptKey),
                                discountCardLM.object(discountCardLM.findClassByCompoundName("DiscountCard")).getMapping(discountCardKey)));
                    }
                    ImportKey<?> receiptReturnDetailKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("ReceiptReturnDetail"), zReportLM.findLCPByCompoundOldName("receiptDetailId").getMapping(idReceiptDetailField));
                    returnProperties.add(new ImportProperty(idReceiptDetailField, zReportLM.findLCPByCompoundOldName("idReceiptDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(numberReceiptDetailField, zReportLM.findLCPByCompoundOldName("numberReceiptDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(idBarcodeReceiptDetailField, zReportLM.findLCPByCompoundOldName("idBarcodeReceiptDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(quantityReceiptReturnDetailField, zReportLM.findLCPByCompoundOldName("quantityReceiptReturnDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(priceReceiptReturnDetailField, zReportLM.findLCPByCompoundOldName("priceReceiptReturnDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(retailSumReceiptReturnDetailField, zReportLM.findLCPByCompoundOldName("sumReceiptReturnDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(discountSumReceiptReturnDetailField, zReportLM.findLCPByCompoundOldName("discountSumReceiptReturnDetail").getMapping(receiptReturnDetailKey)));
                    returnProperties.add(new ImportProperty(idReceiptField, zReportLM.findLCPByCompoundOldName("receiptReceiptDetail").getMapping(receiptReturnDetailKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("Receipt")).getMapping(receiptKey)));

                    returnProperties.add(new ImportProperty(idBarcodeReceiptDetailField, zReportLM.findLCPByCompoundOldName("skuReceiptReturnDetail").getMapping(receiptReturnDetailKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("Sku")).getMapping(skuKey)));

                    List<List<Object>> dataSale = new ArrayList<List<Object>>();
                    List<List<Object>> dataReturn = new ArrayList<List<Object>>();

                    List<List<Object>> dataPayment = new ArrayList<List<Object>>();

                    for (SalesInfo sale : data) {
                        String idZReport = sale.numberGroupCashRegister + "_" + sale.numberCashRegister + "_" + sale.numberZReport; 
                        String idReceipt = sale.numberGroupCashRegister + "_" + sale.numberCashRegister + "_" + sale.numberZReport + "_" + sale.numberReceipt;
                        String idReceiptDetail = sale.numberGroupCashRegister + "_" + sale.numberCashRegister + "_"  + sale.numberZReport + "_" + sale.numberReceipt + "_" + sale.numberReceiptDetail;
                        if (sale.quantityReceiptDetail.doubleValue() < 0) {
                            List<Object> row = Arrays.<Object>asList(sale.numberGroupCashRegister, sale.numberCashRegister, idZReport, sale.numberZReport,
                                    sale.dateReceipt, sale.timeReceipt, true, idReceipt, sale.numberReceipt,
                                    idReceiptDetail, sale.numberReceiptDetail, sale.barcodeItem, sale.quantityReceiptDetail.negate(),
                                    sale.priceReceiptDetail, sale.sumReceiptDetail.negate(), sale.discountSumReceiptDetail,
                                    sale.discountSumReceipt);
                            if (discountCardLM != null)
                                row.add(sale.seriesNumberDiscountCard);
                            dataReturn.add(row);
                        } else {
                            List<Object> row = Arrays.<Object>asList(sale.numberGroupCashRegister, sale.numberCashRegister, idZReport, sale.numberZReport,
                                    sale.dateReceipt, sale.timeReceipt, true, idReceipt, sale.numberReceipt,
                                    idReceiptDetail, sale.numberReceiptDetail, sale.barcodeItem, sale.quantityReceiptDetail,
                                    sale.priceReceiptDetail, sale.sumReceiptDetail, sale.discountSumReceiptDetail,
                                    sale.discountSumReceipt);
                            if (discountCardLM != null)
                                row.add(sale.seriesNumberDiscountCard);
                            dataSale.add(row);
                        }
                        if (sale.sumCash != null && sale.sumCash.doubleValue() != 0) {
                            dataPayment.add(Arrays.<Object>asList(idReceipt + "1", idReceipt, "cash", sale.sumCash, 1));
                        }
                        if (sale.sumCard != null && sale.sumCard.doubleValue() != 0) {
                            dataPayment.add(Arrays.<Object>asList(idReceipt + "2", idReceipt, "card", sale.sumCard, 2));
                        }
                    }

                    List<ImportField> saleImportFields = Arrays.asList(nppGroupMachineryField, nppMachineryField,
                            idZReportField, numberZReportField, dateReceiptField, timeReceiptField, isPostedZReportField, idReceiptField,
                            numberReceiptField, idReceiptDetailField, numberReceiptDetailField, idBarcodeReceiptDetailField,
                            quantityReceiptSaleDetailField, priceReceiptSaleDetailField, sumReceiptSaleDetailField,
                            discountSumReceiptSaleDetailField, discountSumSaleReceiptField);
                    if (discountCardLM != null)
                        saleImportFields.add(seriesNumberDiscountCardField);

                    List<ImportField> returnImportFields = Arrays.asList(nppGroupMachineryField, nppMachineryField,
                            idZReportField, numberZReportField, dateReceiptField, timeReceiptField, isPostedZReportField, idReceiptField,
                            numberReceiptField, idReceiptDetailField, numberReceiptDetailField, idBarcodeReceiptDetailField,
                            quantityReceiptReturnDetailField, priceReceiptReturnDetailField, retailSumReceiptReturnDetailField,
                            discountSumReceiptReturnDetailField, discountSumReturnReceiptField);
                    if (discountCardLM != null)
                        returnImportFields.add(seriesNumberDiscountCardField);


                    List<ImportKey<?>> saleKeys = Arrays.asList(zReportKey, cashRegisterKey, receiptKey, receiptSaleDetailKey, skuKey);
                    if (discountCardLM != null)
                        saleKeys.add(discountCardKey);

                    session.pushVolatileStats();
                    new IntegrationService(session, new ImportTable(saleImportFields, dataSale), saleKeys, saleProperties).synchronize(true);

                    List<ImportKey<?>> returnKeys = Arrays.asList(zReportKey, cashRegisterKey, receiptKey, receiptReturnDetailKey, skuKey);
                    if (discountCardLM != null)
                        returnKeys.add(discountCardKey);
                    new IntegrationService(session, new ImportTable(returnImportFields, dataReturn), returnKeys, returnProperties).synchronize(true);

                    ImportKey<?> paymentKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("ZReport.Payment"), zReportLM.findLCPByCompoundOldName("ZReport.paymentId").getMapping(idPaymentField));
                    ImportKey<?> paymentTypeKey = new ImportKey((ConcreteCustomClass) zReportLM.findClassByCompoundName("PaymentType"), zReportLM.findLCPByCompoundOldName("typePaymentSID").getMapping(sidTypePaymentField));
                    paymentProperties.add(new ImportProperty(idPaymentField, zReportLM.findLCPByCompoundOldName("ZReport.idPayment").getMapping(paymentKey)));
                    paymentProperties.add(new ImportProperty(sumPaymentField, zReportLM.findLCPByCompoundOldName("ZReport.sumPayment").getMapping(paymentKey)));
                    paymentProperties.add(new ImportProperty(numberPaymentField, zReportLM.findLCPByCompoundOldName("ZReport.numberPayment").getMapping(paymentKey)));
                    paymentProperties.add(new ImportProperty(sidTypePaymentField, zReportLM.findLCPByCompoundOldName("paymentTypePayment").getMapping(paymentKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("PaymentType")).getMapping(paymentTypeKey)));
                    paymentProperties.add(new ImportProperty(idReceiptField, zReportLM.findLCPByCompoundOldName("receiptPayment").getMapping(paymentKey),
                            zReportLM.object(zReportLM.findClassByCompoundName("Receipt")).getMapping(receiptKey)));

                    List<ImportField> paymentImportFields = Arrays.asList(idPaymentField, idReceiptField, sidTypePaymentField,
                            sumPaymentField, numberPaymentField);

                    String message = "Загружено записей: " + (dataSale.size() + dataReturn.size());
                    List<Integer> cashRegisterNumbers = new ArrayList<Integer>();
                    List<String> fileNames = new ArrayList<String>();
                    Set<String> dates = new HashSet<String>();
                    for (SalesInfo salesInfo : data) {
                        if (!cashRegisterNumbers.contains(salesInfo.numberCashRegister))
                            cashRegisterNumbers.add(salesInfo.numberCashRegister);
                        if ((salesInfo.filename != null) && (!fileNames.contains(salesInfo.filename.trim())))
                            fileNames.add(salesInfo.filename.trim());
                        if(salesInfo.dateReceipt != null)
                            dates.add(new SimpleDateFormat("dd.MM.yyyy").format(salesInfo.dateReceipt));
                    }
                    message += "\nИз касс: ";
                    for (Integer cashRegisterNumber : cashRegisterNumbers)
                        message += cashRegisterNumber + ", ";
                    message = message.substring(0, message.length() - 2);

                    message += "\nИз файлов: ";
                    for (String filename : fileNames)
                        message += filename + ", ";
                    message = message.substring(0, message.length() - 2);
                    
                    if(!dates.isEmpty()) {
                        message += "\nЗа даты: ";
                        for (String date : dates)
                            message += date + ", ";
                        message = message.substring(0, message.length() - 2);
                    }

                    DataObject logObject = session.addObject((ConcreteCustomClass) equLM.findClassByCompoundName("EquipmentServerLog"));
                    Object equipmentServerObject = equLM.findLCPByCompoundOldName("sidToEquipmentServer").read(session, new DataObject(equipmentServer, StringClass.get(20)));
                    equLM.findLCPByCompoundOldName("equipmentServerEquipmentServerLog").change(equipmentServerObject, session, logObject);
                    equLM.findLCPByCompoundOldName("dataEquipmentServerLog").change(message, session, logObject);
                    equLM.findLCPByCompoundOldName("dateEquipmentServerLog").change(DateConverter.dateToStamp(Calendar.getInstance().getTime()), session, logObject);

                    new IntegrationService(session, new ImportTable(paymentImportFields, dataPayment), Arrays.asList(paymentKey, paymentTypeKey, receiptKey),
                            paymentProperties).synchronize(true);

                    String result = session.applyMessage(getBusinessLogics());
                    session.popVolatileStats();
                    if(result != null)
                        return result;
                }
            } else return null;

        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void succeedTransaction(Integer transactionID) throws RemoteException, SQLException {
        try {
            DataSession session = getDbManager().createSession();
            equLM.findLCPByCompoundOldName("succeededMachineryPriceTransaction").change(true, session,
                    session.getDataObject(equLM.findClassByCompoundName("MachineryPriceTransaction"), transactionID));
            session.apply(getBusinessLogics());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<byte[][]> readLabelFormats(List<String> scalesModelsList) throws RemoteException, SQLException {
        try {
            DataSession session = getDbManager().createSession();

            List<byte[][]> fileLabelFormats = new ArrayList<byte[][]>();

            if (scalesLM != null) {

                for (String scalesModel : scalesModelsList) {

                    DataObject scalesModelObject = new DataObject(scalesLM.findLCPByCompoundOldName("scalesModelName").read(session, new DataObject(scalesModel)), (ConcreteClass) scalesLM.findClassByCompoundName("scalesModel"));

                    LCP<PropertyInterface> isLabelFormat = (LCP<PropertyInterface>) scalesLM.is(scalesLM.findClassByCompoundName("LabelFormat"));

                    ImRevMap<PropertyInterface, KeyExpr> labelFormatKeys = isLabelFormat.getMapKeys();
                    KeyExpr labelFormatKey = labelFormatKeys.singleValue();
                    QueryBuilder<PropertyInterface, Object> labelFormatQuery = new QueryBuilder<PropertyInterface, Object>(labelFormatKeys);

                    labelFormatQuery.addProperty("fileLabelFormat", scalesLM.findLCPByCompoundOldName("fileLabelFormat").getExpr(labelFormatKey));
                    labelFormatQuery.addProperty("fileMessageLabelFormat", scalesLM.findLCPByCompoundOldName("fileMessageLabelFormat").getExpr(labelFormatKey));
                    labelFormatQuery.and(isLabelFormat.property.getExpr(labelFormatKeys).getWhere());
                    labelFormatQuery.and(scalesLM.findLCPByCompoundOldName("scalesModelLabelFormat").getExpr(labelFormatKey).compare((scalesModelObject).getExpr(), Compare.EQUALS));

                    ImOrderMap<ImMap<PropertyInterface, Object>, ImMap<Object, Object>> labelFormatResult = labelFormatQuery.execute(session);

                    for (ImMap<Object, Object> row : labelFormatResult.valueIt()) {
                        byte[] fileLabelFormat = (byte[]) row.get("fileLabelFormat");
                        byte[] fileMessageLabelFormat = (byte[]) row.get("fileMessageLabelFormat");
                        fileLabelFormats.add(new byte[][]{fileLabelFormat, fileMessageLabelFormat});
                    }
                }
            }
            return fileLabelFormats;
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw Throwables.propagate(e);
        } catch (SQLHandledException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void errorTransactionReport(Integer transactionID, Exception e) throws RemoteException, SQLException {
        try {
            DataSession session = getDbManager().createSession();
            DataObject errorObject = session.addObject((ConcreteCustomClass) equLM.findClassByCompoundName("MachineryPriceTransactionError"));
            equLM.findLCPByCompoundOldName("machineryPriceTransactionMachineryPriceTransactionError").change(transactionID, session, errorObject);
            equLM.findLCPByCompoundOldName("dataMachineryPriceTransactionError").change(e.toString(), session, errorObject);
            equLM.findLCPByCompoundOldName("dateMachineryPriceTransactionError").change(DateConverter.dateToStamp(Calendar.getInstance().getTime()), session, errorObject);
            OutputStream os = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(os));
            equLM.findLCPByCompoundOldName("errorTraceMachineryPriceTransactionError").change(os.toString(), session, errorObject);

            session.apply(getBusinessLogics());
        } catch (Exception e2) {
            throw Throwables.propagate(e2);
        }
    }

    @Override
    public void errorEquipmentServerReport(String equipmentServer, Throwable exception) throws
            RemoteException, SQLException {
        try {
            DataSession session = getDbManager().createSession();
            DataObject errorObject = session.addObject((ConcreteCustomClass) equLM.findClassByCompoundName("EquipmentServerError"));
            Object equipmentServerObject = equLM.findLCPByCompoundOldName("sidToEquipmentServer").read(session, new DataObject(equipmentServer, StringClass.get(20)));
            equLM.findLCPByCompoundOldName("equipmentServerEquipmentServerError").change(equipmentServerObject, session, errorObject);
            equLM.findLCPByCompoundOldName("dataEquipmentServerError").change(exception.toString(), session, errorObject);
            OutputStream os = new ByteArrayOutputStream();
            exception.printStackTrace(new PrintStream(os));
            equLM.findLCPByCompoundOldName("erTraceEquipmentServerError").change(os.toString(), session, errorObject);

            equLM.findLCPByCompoundOldName("dateEquipmentServerError").change(DateConverter.dateToStamp(Calendar.getInstance().getTime()), session, errorObject);

            session.apply(getBusinessLogics());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public EquipmentServerSettings readEquipmentServerSettings(String equipmentServer) throws RemoteException, SQLException {
        try {
            ThreadLocalContext.set(logicsInstance.getContext());
            DataSession session = getDbManager().createSession();
            Integer equipmentServerID = (Integer) equLM.findLCPByCompoundOldName("sidToEquipmentServer").read(session, new DataObject(equipmentServer, StringClass.get(20)));
            if (equipmentServerID != null) {
                DataObject equipmentServerObject = new DataObject(equipmentServerID, (ConcreteClass) equLM.findClassByCompoundName("EquipmentServer"));
                Integer delay = (Integer) equLM.findLCPByCompoundOldName("delayEquipmentServer").read(session, equipmentServerObject);
                Integer numberAtATime = (Integer) equLM.findLCPByCompoundOldName("numberAtATimeEquipmentServer").read(session, equipmentServerObject);
                return new EquipmentServerSettings(delay, numberAtATime);
            } else return null;
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private List<DataObject> readGroupMachineryObjectList(DataSession session, String equServerID) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        LCP<PropertyInterface> isGroupMachinery = (LCP<PropertyInterface>) equLM.is(equLM.findClassByCompoundName("GroupMachinery"));
        ImRevMap<PropertyInterface, KeyExpr> keys = isGroupMachinery.getMapKeys();
        KeyExpr key = keys.singleValue();
        QueryBuilder<PropertyInterface, Object> query = new QueryBuilder<PropertyInterface, Object>(keys);
        query.and(equLM.findLCPByCompoundOldName("sidEquipmentServerGroupMachinery").getExpr(key).compare(new DataObject(equServerID, StringClass.get(20)), Compare.EQUALS));

        ImOrderMap<ImMap<PropertyInterface, DataObject>, ImMap<Object, ObjectValue>> result = query.executeClasses(session);
        List<DataObject> groupMachineryObjectList = new ArrayList<DataObject>();
        for (ImMap<PropertyInterface, DataObject> entry : result.keyIt()) {
            DataObject groupMachineryObject = entry.getValue(0);
            groupMachineryObjectList.add(groupMachineryObject);
        }
        return groupMachineryObjectList;
    }

    private String dateTimeCode(Timestamp timeStamp) {
        String result = "";
        long time = timeStamp.getTime() / 1000;
        while (time > 26) {
            result = (char) (time % 26 + 97) + result;
            time = time / 26;
        }
        result = (char) (time + 97) + result;
        return result;
    }

    private String trim(String input) {
        return input == null ? null : input.trim();
    }

    private List<List<Object>> initData(int size) {
        List<List<Object>> data = new ArrayList<List<Object>>();
        for (int i = 0; i < size; i++) {
            data.add(new ArrayList<Object>());
        }
        return data;
    }

    @Aspect
    private static class RemoteLogicsContextHoldingAspect {
        @Before("execution(* equ.api.EquipmentServerInterface.*(..)) && target(remoteLogics)")
        public void beforeCall(EquipmentServer remoteLogics) {
            ThreadLocalContext.set(remoteLogics.logicsInstance.getContext());
        }

        @AfterReturning("execution(* equ.api.EquipmentServerInterface.*(..)) && target(remoteLogics)")
        public void afterReturning(EquipmentServer remoteLogics) {
            ThreadLocalContext.set(null);
        }
    }
}
