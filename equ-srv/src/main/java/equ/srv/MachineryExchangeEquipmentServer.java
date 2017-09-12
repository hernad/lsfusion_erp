package equ.srv;

import com.google.common.base.Throwables;
import equ.api.MachineryInfo;
import equ.api.RequestExchange;
import equ.api.cashregister.CashRegisterInfo;
import equ.api.cashregister.CashierInfo;
import equ.api.cashregister.DiscountCard;
import equ.api.terminal.TerminalOrder;
import lsfusion.base.DateConverter;
import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.interop.Compare;
import lsfusion.server.classes.*;
import lsfusion.server.context.ExecutionStack;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.logics.BusinessLogics;
import lsfusion.server.logics.DBManager;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.linear.LCP;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.trim;

public class MachineryExchangeEquipmentServer {

    static ScriptingLogicsModule cashRegisterLM;
    static ScriptingLogicsModule discountCardLM;
    static ScriptingLogicsModule equLM;
    static ScriptingLogicsModule purchaseInvoiceAgreementLM;
    static ScriptingLogicsModule machineryLM;
    static ScriptingLogicsModule machineryPriceTransactionLM;

    public static void init(BusinessLogics BL) {
        cashRegisterLM = BL.getModule("EquipmentCashRegister");
        discountCardLM = BL.getModule("DiscountCard");
        equLM = BL.getModule("Equipment");
        purchaseInvoiceAgreementLM = BL.getModule("PurchaseInvoiceAgreement");
        machineryLM = BL.getModule("Machinery");
        machineryPriceTransactionLM = BL.getModule("MachineryPriceTransaction");
    }

    public static List<RequestExchange> readRequestExchange(DBManager dbManager, BusinessLogics BL, ExecutionStack stack) throws RemoteException, SQLException {

        List<RequestExchange> requestExchangeList = new ArrayList();
        if(machineryLM != null && machineryPriceTransactionLM != null) {

            try (DataSession session = dbManager.createSession()) {

                KeyExpr requestExchangeExpr = new KeyExpr("requestExchange");
                ImRevMap<Object, KeyExpr> requestExchangeKeys = MapFact.singletonRev((Object) "requestExchange", requestExchangeExpr);
                QueryBuilder<Object, Object> requestExchangeQuery = new QueryBuilder<>(requestExchangeKeys);

                String[] requestExchangeNames = new String[]{"dateFromRequestExchange", "dateToRequestExchange", "startDateRequestExchange",
                        "nameRequestExchangeTypeRequestExchange", "idDiscountCardFromRequestExchange", "idDiscountCardToRequestExchange"};
                LCP[] requestExchangeProperties = machineryPriceTransactionLM.findProperties("dateFrom[RequestExchange]", "dateTo[RequestExchange]", "startDate[RequestExchange]",
                        "nameRequestExchangeType[RequestExchange]", "idDiscountCardFrom[RequestExchange]", "idDiscountCardTo[RequestExchange]");
                for (int i = 0; i < requestExchangeProperties.length; i++) {
                    requestExchangeQuery.addProperty(requestExchangeNames[i], requestExchangeProperties[i].getExpr(requestExchangeExpr));
                }
                requestExchangeQuery.and(machineryPriceTransactionLM.findProperty("notSucceeded[RequestExchange]").getExpr(requestExchangeExpr).getWhere());
                ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> requestExchangeResult = requestExchangeQuery.executeClasses(session);
                for (int i = 0; i < requestExchangeResult.size(); i++) {

                    DataObject requestExchangeObject = requestExchangeResult.getKey(i).get("requestExchange");
                    Date dateFromRequestExchange = (Date) requestExchangeResult.getValue(i).get("dateFromRequestExchange").getValue();
                    Date dateToRequestExchange = (Date) requestExchangeResult.getValue(i).get("dateToRequestExchange").getValue();
                    Date startDateRequestExchange = (Date) requestExchangeResult.getValue(i).get("startDateRequestExchange").getValue();
                    String idDiscountCardFrom = trim((String) requestExchangeResult.getValue(i).get("idDiscountCardFromRequestExchange").getValue());
                    String idDiscountCardTo = trim((String) requestExchangeResult.getValue(i).get("idDiscountCardToRequestExchange").getValue());
                    String typeRequestExchange = trim((String) requestExchangeResult.getValue(i).get("nameRequestExchangeTypeRequestExchange").getValue());

                    Set<CashRegisterInfo> cashRegisterSet = new HashSet<> ();
                    Map<String, Set<String>> directoryStockMap = readExtraStockRequestExchange(session, requestExchangeObject);
                    String idStock = null;

                    KeyExpr machineryExpr = new KeyExpr("machinery");
                    ImRevMap<Object, KeyExpr> machineryKeys = MapFact.singletonRev((Object) "machinery", machineryExpr);
                    QueryBuilder<Object, Object> machineryQuery = new QueryBuilder<>(machineryKeys);

                    String[] machineryNames = new String[]{"overDirectoryMachinery", "idStockMachinery", "nppMachinery", "handlerModelMachinery"};
                    LCP[] machineryProperties = machineryPriceTransactionLM.findProperties("overDirectory[Machinery]", "idStock[Machinery]",
                            "npp[Machinery]", "handlerModel[Machinery]");
                    for (int j = 0; j < machineryProperties.length; j++) {
                        machineryQuery.addProperty(machineryNames[j], machineryProperties[j].getExpr(machineryExpr));
                    }
                    machineryQuery.and(machineryPriceTransactionLM.findProperty("in[Machinery,RequestExchange]").getExpr(machineryExpr, requestExchangeObject.getExpr()).getWhere());
                    machineryQuery.and(machineryLM.findProperty("stock[Machinery]").getExpr(machineryExpr).compare(
                            machineryPriceTransactionLM.findProperty("stock[RequestExchange]").getExpr(requestExchangeObject.getExpr()), Compare.EQUALS));
                    machineryQuery.and(machineryLM.findProperty("inactive[Machinery]").getExpr(machineryExpr).getWhere().not());
                    ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> result = machineryQuery.executeClasses(session);
                    for (int j = 0; j < result.size(); j++) {

                        String directoryMachinery = trim((String) result.getValue(j).get("overDirectoryMachinery").getValue());
                        idStock = trim((String) result.getValue(j).get("idStockMachinery").getValue());
                        Integer nppMachinery = (Integer) result.getValue(j).get("nppMachinery").getValue();
                        String handlerModelMachinery = trim((String) result.getValue(j).get("handlerModelMachinery").getValue());

                        ConcreteClass machineryClass = result.getKey(j).get("machinery").objectClass;
                        ValueClass cashRegisterClass = cashRegisterLM == null ? null : cashRegisterLM.findClass("CashRegister");
                        if(machineryClass != null && machineryClass.equals(cashRegisterClass))
                            cashRegisterSet.add(new CashRegisterInfo(null, nppMachinery, handlerModelMachinery, null, directoryMachinery, null, null));
                        putDirectoryStockMap(directoryStockMap, directoryMachinery, idStock);
                    }

                    requestExchangeList.add(new RequestExchange((Long) requestExchangeObject.getValue(), cashRegisterSet,
                            idStock, directoryStockMap, dateFromRequestExchange, dateToRequestExchange,
                            startDateRequestExchange, idDiscountCardFrom, idDiscountCardTo, typeRequestExchange));
                }
                session.apply(BL, stack);
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
        return requestExchangeList;
    }

    private static Map<String, Set<String>> readExtraStockRequestExchange(DataSession session, DataObject requestExchangeObject) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        Map<String, Set<String>> directoryStockMap = new HashMap<>();
        KeyExpr stockExpr = new KeyExpr("stock");
        KeyExpr machineryExpr = new KeyExpr("machinery");
        ImRevMap<Object, KeyExpr> keys = MapFact.toRevMap((Object) "stock", stockExpr, "machinery", machineryExpr);
        QueryBuilder<Object, Object> query = new QueryBuilder<>(keys);

        query.addProperty("idStock", machineryPriceTransactionLM.findProperty("id[Stock]").getExpr(stockExpr));
        query.addProperty("overDirectoryMachinery", machineryPriceTransactionLM.findProperty("overDirectory[Machinery]").getExpr(machineryExpr));
        query.and(machineryPriceTransactionLM.findProperty("in[Stock,RequestExchange]").getExpr(stockExpr, requestExchangeObject.getExpr()).getWhere());
        query.and(machineryPriceTransactionLM.findProperty("overDirectory[Machinery]").getExpr(machineryExpr).getWhere());
        query.and(machineryLM.findProperty("stock[Machinery]").getExpr(machineryExpr).compare(stockExpr, Compare.EQUALS));
        query.and(machineryLM.findProperty("inactive[Machinery]").getExpr(machineryExpr).getWhere().not());
        ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);
        for (ImMap<Object, Object> entry : result.values())
            putDirectoryStockMap(directoryStockMap, trim((String) entry.get("overDirectoryMachinery")), trim((String) entry.get("idStock")));
        return directoryStockMap;
    }

    private static void putDirectoryStockMap(Map<String, Set<String>> directoryStockMap, String directory, String idStock) {
        if(directory != null) {
            Set<String> stockSet = directoryStockMap.get(directory);
            if (stockSet == null)
                stockSet = new HashSet();
            stockSet.add(idStock);
            directoryStockMap.put(directory, stockSet);
        }
    }

    public static void errorRequestExchange(DBManager dbManager, BusinessLogics BL, ExecutionStack stack, Map<Long, Throwable> failedRequestsMap) throws RemoteException, SQLException {
        if (machineryPriceTransactionLM != null) {
            try (DataSession session = dbManager.createSession()) {
                for (Map.Entry<Long, Throwable> request : failedRequestsMap.entrySet()) {
                    errorRequestExchange(session, request.getKey(), request.getValue());
                }
                session.apply(BL, stack);
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    public static void errorRequestExchange(DBManager dbManager, BusinessLogics BL, ExecutionStack stack, Long requestExchange, Throwable t) throws RemoteException, SQLException {
        if (machineryPriceTransactionLM != null) {
            try (DataSession session = dbManager.createSession()) {
                errorRequestExchange(session, requestExchange, t);
                session.apply(BL, stack);
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    private static void errorRequestExchange(DataSession session, Long requestExchange, Throwable t) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        DataObject errorObject = session.addObject((ConcreteCustomClass) machineryPriceTransactionLM.findClass("RequestExchangeError"));
        machineryPriceTransactionLM.findProperty("date[RequestExchangeError]").change(getCurrentTimestamp(), session, errorObject);
        OutputStream os = new ByteArrayOutputStream();
        t.printStackTrace(new PrintStream(os));
        machineryPriceTransactionLM.findProperty("erTrace[RequestExchangeError]").change(os.toString(), session, errorObject);
        machineryPriceTransactionLM.findProperty("requestExchange[RequestExchangeError]").change(requestExchange, session, errorObject);
    }

    public static void finishRequestExchange(DBManager dbManager, BusinessLogics BL, ExecutionStack stack, Set<Long> succeededRequestsSet) throws RemoteException, SQLException {
        if (machineryPriceTransactionLM != null) {
            try (DataSession session = dbManager.createSession()) {
                for (Long request : succeededRequestsSet) {
                    DataObject requestExchangeObject = new DataObject(request, (ConcreteCustomClass) machineryPriceTransactionLM.findClass("RequestExchange"));
                    machineryPriceTransactionLM.findProperty("succeeded[RequestExchange]").change(true, session, requestExchangeObject);
                    machineryPriceTransactionLM.findProperty("dateTimeSucceeded[RequestExchange]").change(getCurrentTimestamp(), session, requestExchangeObject);
                }
                session.apply(BL, stack);
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    protected static Timestamp getCurrentTimestamp() {
        return DateConverter.dateToStamp(Calendar.getInstance().getTime());
    }

    public static List<DiscountCard> readDiscountCardList(DBManager dbManager, RequestExchange requestExchange) throws RemoteException, SQLException {
        List<DiscountCard> discountCardList = new ArrayList<>();
        if(discountCardLM != null) {
            try (DataSession session = dbManager.createSession()) {

                KeyExpr discountCardExpr = new KeyExpr("discountCard");
                ImRevMap<Object, KeyExpr> discountCardKeys = MapFact.singletonRev((Object) "discountCard", discountCardExpr);

                QueryBuilder<Object, Object> discountCardQuery = new QueryBuilder<>(discountCardKeys);
                String[] discountCardNames = new String[]{"idDiscountCard", "numberDiscountCard", "nameDiscountCard",
                        "percentDiscountCard", "dateDiscountCard", "dateToDiscountCard", "initialSumDiscountCard",
                        "idDiscountCardType", "nameDiscountCardType", "firstNameContact", "lastNameContact", "middleNameContact", "birthdayContact",
                        "sexContact"};
                LCP[] discountCardProperties = discountCardLM.findProperties("id[DiscountCard]", "number[DiscountCard]", "name[DiscountCard]",
                        "percent[DiscountCard]", "date[DiscountCard]", "dateTo[DiscountCard]", "initialSum[DiscountCard]",
                        "idDiscountCardType[DiscountCard]", "nameDiscountCardType[DiscountCard]", "firstNameContact[DiscountCard]", "lastNameContact[DiscountCard]",
                        "middleNameHttpServerContact[DiscountCard]", "birthdayContact[DiscountCard]", "numberSexHttpServerContact[DiscountCard]");
                for (int i = 0; i < discountCardProperties.length; i++) {
                    discountCardQuery.addProperty(discountCardNames[i], discountCardProperties[i].getExpr(discountCardExpr));
                }
                discountCardQuery.and(discountCardLM.findProperty("number[DiscountCard]").getExpr(discountCardExpr).getWhere());
                discountCardQuery.and(discountCardLM.findProperty("skipLoad[DiscountCard]").getExpr(discountCardExpr).getWhere().not());
                Long numberFrom = parseLong(requestExchange.idDiscountCardFrom);
                if (numberFrom != null)
                    discountCardQuery.and(discountCardLM.findProperty("longNumber[DiscountCard]").getExpr(discountCardExpr).compare(new DataObject(numberFrom, LongClass.instance).getExpr(), Compare.GREATER_EQUALS));
                Long numberTo = parseLong(requestExchange.idDiscountCardTo);
                if (numberTo != null)
                    discountCardQuery.and(discountCardLM.findProperty("longNumber[DiscountCard]").getExpr(discountCardExpr).compare(new DataObject(numberTo, LongClass.instance).getExpr(), Compare.LESS_EQUALS));
                if(requestExchange.startDate != null)
                    discountCardQuery.and(discountCardLM.findProperty("date[DiscountCard]").getExpr(discountCardExpr).compare(new DataObject(requestExchange.startDate, DateClass.instance).getExpr(), Compare.GREATER_EQUALS));

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> discountCardResult = discountCardQuery.execute(session, MapFact.singletonOrder((Object) "idDiscountCard", false));

                for (int i = 0, size = discountCardResult.size(); i < size; i++) {
                    ImMap<Object, Object> row = discountCardResult.getValue(i);

                    String idDiscountCard = getRowValue(row, "idDiscountCard");
                    if (idDiscountCard == null)
                        idDiscountCard = String.valueOf(discountCardResult.getKey(i).get("discountCard"));
                    String numberDiscountCard = getRowValue(row, "numberDiscountCard");
                    String nameDiscountCard = getRowValue(row, "nameDiscountCard");
                    BigDecimal percentDiscountCard = (BigDecimal) row.get("percentDiscountCard");
                    BigDecimal initialSumDiscountCard = (BigDecimal) row.get("initialSumDiscountCard");
                    Date dateFromDiscountCard = (Date) row.get("dateDiscountCard");
                    Date dateToDiscountCard = (Date) row.get("dateToDiscountCard");
                    String idDiscountCardType = (String) row.get("idDiscountCardType");
                    String nameDiscountCardType = (String) row.get("nameDiscountCardType");
                    String firstNameContact = (String) row.get("firstNameContact");
                    String lastNameContact = (String) row.get("lastNameContact");
                    String middleNameContact = (String) row.get("middleNameContact");
                    Date birthdayContact = (Date) row.get("birthdayContact");
                    Integer sexContact = (Integer) row.get("sexContact");
                    discountCardList.add(new DiscountCard(idDiscountCard, numberDiscountCard, nameDiscountCard,
                            percentDiscountCard, initialSumDiscountCard, dateFromDiscountCard, dateToDiscountCard,
                            idDiscountCardType, nameDiscountCardType, firstNameContact, lastNameContact, middleNameContact, birthdayContact,
                            sexContact, true));
                }
            } catch (Exception e) {
                throw Throwables.propagate(e);
            }
        }
        return discountCardList;
    }

    public static List<CashierInfo> readCashierInfoList(DBManager dbManager) throws RemoteException, SQLException {
        List<CashierInfo> cashierInfoList = new ArrayList<>();
        if(equLM != null) {
            try (DataSession session = dbManager.createSession()) {

                KeyExpr employeeExpr = new KeyExpr("employee");
                ImRevMap<Object, KeyExpr> employeeKeys = MapFact.singletonRev((Object) "employee", employeeExpr);

                QueryBuilder<Object, Object> employeeQuery = new QueryBuilder<>(employeeKeys);
                String[] employeeNames = new String[]{"idEmployee", "shortNameContact", "idPositionEmployee", "idStockEmployee"};
                LCP[] employeeProperties = equLM.findProperties("id[Employee]", "shortName[Contact]", "idPosition[Employee]", "idStock[Employee]");
                for (int i = 0; i < employeeProperties.length; i++) {
                    employeeQuery.addProperty(employeeNames[i], employeeProperties[i].getExpr(employeeExpr));
                }
                employeeQuery.and(equLM.findProperty("idStock[Employee]").getExpr(employeeExpr).getWhere());
                employeeQuery.and(equLM.findProperty("id[Employee]").getExpr(employeeExpr).getWhere());
                employeeQuery.and(equLM.findProperty("shortName[Contact]").getExpr(employeeExpr).getWhere());

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> employeeResult = employeeQuery.execute(session);

                for (int i = 0, size = employeeResult.size(); i < size; i++) {
                    ImMap<Object, Object> row = employeeResult.getValue(i);

                    String numberCashier = getRowValue(row, "idEmployee");
                    String nameCashier = getRowValue(row, "shortNameContact");
                    String idPosition = getRowValue(row, "idPositionEmployee");
                    String idStock = getRowValue(row, "idStockEmployee");
                    cashierInfoList.add(new CashierInfo(numberCashier, nameCashier, idPosition, idStock));
                }
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
        return cashierInfoList;
    }

    public static List<TerminalOrder> readTerminalOrderList(DBManager dbManager, RequestExchange requestExchange) throws RemoteException, SQLException {
        List<TerminalOrder> terminalOrderList = new ArrayList<>();
        if (purchaseInvoiceAgreementLM != null) {
            try (DataSession session = dbManager.createSession()) {
                KeyExpr orderExpr = new KeyExpr("order");
                KeyExpr orderDetailExpr = new KeyExpr("orderDetail");
                ImRevMap<Object, KeyExpr> orderKeys = MapFact.toRevMap((Object) "Order", orderExpr, "OrderDetail", orderDetailExpr);
                QueryBuilder<Object, Object> orderQuery = new QueryBuilder<>(orderKeys);
                String[] orderNames = new String[]{"dateOrder", "numberOrder", "idSupplierOrder"};
                LCP<?>[] orderProperties = purchaseInvoiceAgreementLM.findProperties("date[Purchase.Order]", "number[Purchase.Order]", "idTerminalLegalEntity[Order.Order]");
                for (int i = 0; i < orderProperties.length; i++) {
                    orderQuery.addProperty(orderNames[i], orderProperties[i].getExpr(orderExpr));
                }
                String[] orderDetailNames = new String[]{"idBarcodeSkuOrderDetail", "nameSkuOrderDetail", "priceOrderDetail",
                        "quantityOrderDetail", "minDeviationQuantityOrderDetail", "maxDeviationQuantityOrderDetail",
                        "minDeviationPriceOrderDetail", "maxDeviationPriceOrderDetail"};
                LCP<?>[] orderDetailProperties = purchaseInvoiceAgreementLM.findProperties("idBarcodeSku[Purchase.OrderDetail]", "nameSku[Purchase.OrderDetail]", "price[Purchase.OrderDetail]",
                        "quantity[Purchase.OrderDetail]", "minDeviationQuantity[Purchase.OrderDetail]", "maxDeviationQuantity[Purchase.OrderDetail]",
                        "minDeviationPrice[Purchase.OrderDetail]", "maxDeviationPrice[Purchase.OrderDetail]");
                for (int i = 0; i < orderDetailProperties.length; i++) {
                    orderQuery.addProperty(orderDetailNames[i], orderDetailProperties[i].getExpr(orderDetailExpr));
                }
                if (requestExchange.dateFrom != null)
                    orderQuery.and(purchaseInvoiceAgreementLM.findProperty("date[Purchase.Order]").getExpr(orderExpr).compare(
                            new DataObject(requestExchange.dateFrom, DateClass.instance).getExpr(), Compare.GREATER_EQUALS));
                if (requestExchange.dateTo != null)
                    orderQuery.and(purchaseInvoiceAgreementLM.findProperty("date[Purchase.Order]").getExpr(orderExpr).compare(
                            new DataObject(requestExchange.dateTo, DateClass.instance).getExpr(), Compare.LESS_EQUALS));
                if (requestExchange.idStock != null)
                    orderQuery.and(purchaseInvoiceAgreementLM.findProperty("customerStock[Purchase.Order]").getExpr(orderExpr).compare(
                            purchaseInvoiceAgreementLM.findProperty("stock[VARSTRING[100]]").readClasses(session, new DataObject(requestExchange.idStock)).getExpr(), Compare.EQUALS));
                orderQuery.and(purchaseInvoiceAgreementLM.findProperty("order[Purchase.OrderDetail]").getExpr(orderDetailExpr).compare(orderExpr, Compare.EQUALS));
                orderQuery.and(purchaseInvoiceAgreementLM.findProperty("number[Purchase.Order]").getExpr(orderExpr).getWhere());
                orderQuery.and(purchaseInvoiceAgreementLM.findProperty("isOpened[Purchase.Order]").getExpr(orderExpr).getWhere());
                orderQuery.and(purchaseInvoiceAgreementLM.findProperty("idBarcodeSku[Purchase.OrderDetail]").getExpr(orderDetailExpr).getWhere());
                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> orderResult = orderQuery.execute(session);
                for (ImMap<Object, Object> entry : orderResult.values()) {
                    Date dateOrder = (Date) entry.get("dateOrder");
                    String numberOrder = trim((String) entry.get("numberOrder"));
                    String idSupplier = trim((String) entry.get("idSupplierOrder"));
                    String barcode = trim((String) entry.get("idBarcodeSkuOrderDetail"));
                    String name = trim((String) entry.get("nameSkuOrderDetail"));
                    BigDecimal price = (BigDecimal) entry.get("priceOrderDetail");
                    BigDecimal quantity = (BigDecimal) entry.get("quantityOrderDetail");
                    BigDecimal minQuantity = (BigDecimal) entry.get("minDeviationQuantityOrderDetail");
                    BigDecimal maxQuantity = (BigDecimal) entry.get("maxDeviationQuantityOrderDetail");
                    BigDecimal minPrice = (BigDecimal) entry.get("minDeviationPriceOrderDetail");
                    BigDecimal maxPrice = (BigDecimal) entry.get("maxDeviationPriceOrderDetail");
                    terminalOrderList.add(new TerminalOrder(dateOrder, numberOrder, idSupplier, barcode, null, name, price,
                            quantity, minQuantity, maxQuantity, minPrice, maxPrice, null, null));
                }
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
        return terminalOrderList;
    }

    public static List<MachineryInfo> readMachineryInfo(DBManager dbManager, String sidEquipmentServer) throws RemoteException, SQLException {
        List<MachineryInfo> machineryInfoList = new ArrayList<>();
        if (machineryLM != null) {
            try (DataSession session = dbManager.createSession()) {

                KeyExpr groupMachineryExpr = new KeyExpr("groupMachinery");
                KeyExpr machineryExpr = new KeyExpr("machinery");

                ImRevMap<Object, KeyExpr> keys = MapFact.toRevMap((Object) "groupMachinery", groupMachineryExpr, "machinery", machineryExpr);
                QueryBuilder<Object, Object> query = new QueryBuilder<>(keys);

                String[] machineryNames = new String[]{"nppMachinery", "portMachinery", "overDirectoryMachinery"};
                LCP[] machineryProperties = machineryLM.findProperties("npp[Machinery]", "port[Machinery]", "overDirectory[Machinery]");
                for (int i = 0; i < machineryProperties.length; i++) {
                    query.addProperty(machineryNames[i], machineryProperties[i].getExpr(machineryExpr));
                }

                String[] groupMachineryNames = new String[]{"nppGroupMachinery", "handlerModelGroupMachinery", "nameModelGroupMachinery"};
                LCP[] groupMachineryProperties = machineryLM.findProperties("npp[GroupMachinery]", "handlerModel[GroupMachinery]", "nameModel[GroupMachinery]");
                for (int i = 0; i < groupMachineryProperties.length; i++) {
                    query.addProperty(groupMachineryNames[i], groupMachineryProperties[i].getExpr(groupMachineryExpr));
                }

                query.and(machineryLM.findProperty("handlerModel[GroupMachinery]").getExpr(groupMachineryExpr).getWhere());
                query.and(machineryLM.findProperty("overDirectory[Machinery]").getExpr(machineryExpr).getWhere());
                query.and(machineryLM.findProperty("groupMachinery[Machinery]").getExpr(machineryExpr).compare(groupMachineryExpr, Compare.EQUALS));
                query.and(equLM.findProperty("sidEquipmentServer[GroupMachinery]").getExpr(groupMachineryExpr).compare(new DataObject(sidEquipmentServer), Compare.EQUALS));

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);

                for (ImMap<Object, Object> row : result.values()) {
                    machineryInfoList.add(new MachineryInfo(true, false, false, (Integer) row.get("nppGroupMachinery"), (Integer) row.get("nppMachinery"),
                            (String) row.get("nameModelGroupMachinery"), (String) row.get("handlerModelGroupMachinery"), trim((String) row.get("portMachinery")),
                            trim((String) row.get("overDirectoryMachinery"))));
                }
            } catch (ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                throw Throwables.propagate(e);
            }
        }
        return machineryInfoList;
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch(Exception e) {
            return null;
        }
    }

    private static String getRowValue(ImMap<Object, Object> row, String key) {
        return trim((String) row.get(key));
    }

}