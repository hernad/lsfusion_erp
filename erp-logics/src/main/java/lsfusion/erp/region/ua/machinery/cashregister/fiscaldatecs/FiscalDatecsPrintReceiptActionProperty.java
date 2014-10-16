package lsfusion.erp.region.ua.machinery.cashregister.fiscaldatecs;

import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.interop.Compare;
import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.classes.ConcreteCustomClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FiscalDatecsPrintReceiptActionProperty extends ScriptingActionProperty {
    private final ClassPropertyInterface receiptInterface;

    public FiscalDatecsPrintReceiptActionProperty(ScriptingLogicsModule LM) throws ScriptingErrorLog.SemanticErrorException {
        super(LM, LM.findClass("Receipt"));

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        receiptInterface = i.next();
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {

        DataObject receiptObject = context.getDataKeyValue(receiptInterface);

        try {

            boolean skipReceipt = findProperty("fiscalSkipReceipt").read(context.getSession(), receiptObject) != null;
            if (skipReceipt) {
                context.apply();
                findAction("createCurrentReceipt").execute(context);
            } else {
                Integer comPort = (Integer) findProperty("comPortCurrentCashRegister").read(context);
                Integer baudRate = (Integer) findProperty("baudRateCurrentCashRegister").read(context);
                Integer placeNumber = (Integer) findProperty("nppMachineryCurrentCashRegister").read(context);
                ObjectValue userObject = findProperty("employeeReceipt").readClasses(context, receiptObject);
                Object operatorNumber = userObject.isNull() ? 0 : findProperty("operatorNumberCurrentCashRegister").read(context, (DataObject) userObject);
                Double sumTotal = (Double) findProperty("sumReceiptDetailReceipt").read(context, receiptObject);
                Double sumDisc = (Double) findProperty("discountSumReceiptDetailReceipt").read(context, receiptObject);
                Double sumCard = null;
                Double sumCash = null;

                KeyExpr paymentExpr = new KeyExpr("payment");
                ImRevMap<Object, KeyExpr> paymentKeys = MapFact.singletonRev((Object) "payment", paymentExpr);

                QueryBuilder<Object, Object> paymentQuery = new QueryBuilder<Object, Object>(paymentKeys);
                paymentQuery.addProperty("sumPayment", findProperty("sumPayment").getExpr(context.getModifier(), paymentExpr));
                paymentQuery.addProperty("paymentMeansPayment", findProperty("paymentMeansPayment").getExpr(context.getModifier(), paymentExpr));

                paymentQuery.and(findProperty("receiptPayment").getExpr(context.getModifier(), paymentQuery.getMapExprs().get("payment")).compare(receiptObject.getExpr(), Compare.EQUALS));

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> paymentResult = paymentQuery.execute(context);
                for (ImMap<Object, Object> paymentValues : paymentResult.valueIt()) {
                    DataObject paymentMeansCashObject = ((ConcreteCustomClass) findClass("PaymentMeans")).getDataObject("paymentMeansCash");
                    DataObject paymentMeansCardObject = ((ConcreteCustomClass) findClass("PaymentMeans")).getDataObject("paymentMeansCard");
                    if (paymentMeansCashObject.getValue().equals(paymentValues.get("paymentMeansPayment"))) {
                        sumCash = (Double) paymentValues.get("sumPayment");
                    } else if (paymentMeansCardObject.getValue().equals(paymentValues.get("paymentMeansPayment"))) {
                        sumCard = (Double) paymentValues.get("sumPayment");
                    }
                }

                KeyExpr receiptDetailExpr = new KeyExpr("receiptDetail");
                ImRevMap<Object, KeyExpr> receiptDetailKeys = MapFact.singletonRev((Object) "receiptDetail", receiptDetailExpr);

                QueryBuilder<Object, Object> receiptDetailQuery = new QueryBuilder<Object, Object>(receiptDetailKeys);
                receiptDetailQuery.addProperty("nameSkuReceiptDetail", findProperty("nameSkuReceiptDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("quantityReceiptSaleDetail", findProperty("quantityReceiptSaleDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("quantityReceiptReturnDetail", findProperty("quantityReceiptReturnDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("priceReceiptDetail", findProperty("priceReceiptDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("idBarcodeReceiptDetail", findProperty("idBarcodeReceiptDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("sumReceiptDetail", findProperty("sumReceiptDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("discountPercentReceiptSaleDetail", findProperty("discountPercentReceiptSaleDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("discountSumReceiptDetail", findProperty("discountSumReceiptDetail").getExpr(context.getModifier(), receiptDetailExpr));
                receiptDetailQuery.addProperty("numberVATReceiptDetail", findProperty("numberVATReceiptDetail").getExpr(context.getModifier(), receiptDetailExpr));

                receiptDetailQuery.and(findProperty("receiptReceiptDetail").getExpr(context.getModifier(), receiptDetailQuery.getMapExprs().get("receiptDetail")).compare(receiptObject.getExpr(), Compare.EQUALS));

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> receiptDetailResult = receiptDetailQuery.execute(context);
                List<ReceiptItem> receiptSaleItemList = new ArrayList<ReceiptItem>();
                List<ReceiptItem> receiptReturnItemList = new ArrayList<ReceiptItem>();
                for (ImMap<Object, Object> receiptDetailValues : receiptDetailResult.valueIt()) {
                    Double price = (Double) receiptDetailValues.get("priceReceiptDetail");
                    Double quantitySale = (Double) receiptDetailValues.get("quantityReceiptSaleDetail");
                    Double quantityReturn = (Double) receiptDetailValues.get("quantityReceiptReturnDetail");
                    String barcode = (String) receiptDetailValues.get("idBarcodeReceiptDetail");
                    String name = (String) receiptDetailValues.get("nameSkuReceiptDetail");
                    Double sumReceiptDetail = (Double) receiptDetailValues.get("sumReceiptDetail");
                    Double discountPercentReceiptSaleDetail = (Double) receiptDetailValues.get("discountPercentReceiptSaleDetail");
                    Double discountSumReceiptDetail = (Double) receiptDetailValues.get("discountSumReceiptDetail");
                    Integer taxNumber = (Integer) receiptDetailValues.get("numberVATReceiptDetail");
                    if (quantitySale != null)
                        receiptSaleItemList.add(new ReceiptItem(price, quantitySale, barcode, name.trim(), sumReceiptDetail,
                                discountPercentReceiptSaleDetail, discountSumReceiptDetail == null ? null : -discountSumReceiptDetail, taxNumber, 1));
                    if (quantityReturn != null)
                        receiptReturnItemList.add(new ReceiptItem(price, quantityReturn, barcode, name.trim(), sumReceiptDetail,
                                discountPercentReceiptSaleDetail, discountSumReceiptDetail == null ? null : -discountSumReceiptDetail, taxNumber, 1));
                }

                if (context.checkApply()) {
                    String result = (String) context.requestUserInteraction(new FiscalDatecsPrintReceiptClientAction(baudRate, comPort, placeNumber, operatorNumber == null ? 1 : (Integer) operatorNumber, new ReceiptInstance(sumDisc, sumCard, sumCash, sumTotal, receiptSaleItemList, receiptReturnItemList)));
                    if (result == null) {
                        context.apply();
                        findAction("createCurrentReceipt").execute(context);
                    } else
                        context.requestUserInteraction(new MessageClientAction(result, "Ошибка"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw new RuntimeException(e);
        }


    }
}
