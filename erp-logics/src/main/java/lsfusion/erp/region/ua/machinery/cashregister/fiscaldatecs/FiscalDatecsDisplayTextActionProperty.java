package lsfusion.erp.region.ua.machinery.cashregister.fiscaldatecs;

import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;

import java.sql.SQLException;
import java.util.*;

public class FiscalDatecsDisplayTextActionProperty extends ScriptingActionProperty {
    private final ClassPropertyInterface receiptDetailInterface;

    public FiscalDatecsDisplayTextActionProperty(ScriptingLogicsModule LM) throws ScriptingErrorLog.SemanticErrorException {
        super(LM, new ValueClass[]{LM.findClassByCompoundName("ReceiptDetail")});

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        receiptDetailInterface = i.next();
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) {

        DataSession session = context.getSession();
        DataObject receiptDetailObject = context.getDataKeyValue(receiptDetailInterface);

        try {
            ObjectValue receiptObject = LM.findLCPByCompoundOldName("receiptReceiptDetail").readClasses(session, receiptDetailObject);
            Integer comPort = (Integer) LM.findLCPByCompoundOldName("comPortCurrentCashRegister").read(session);
            Integer baudRate = (Integer) LM.findLCPByCompoundOldName("baudRateCurrentCashRegister").read(session);

            String name = (String) LM.findLCPByCompoundOldName("nameSkuReceiptDetail").read(session, receiptDetailObject);
            String barcode = (String) LM.findLCPByCompoundOldName("idBarcodeReceiptDetail").read(session, receiptDetailObject);
            Double quantity = (Double) LM.findLCPByCompoundOldName("quantityReceiptDetail").read(session, receiptDetailObject);
            Double price = (Double) LM.findLCPByCompoundOldName("priceReceiptDetail").read(session, receiptDetailObject);
            Double sum = (Double) LM.findLCPByCompoundOldName("sumReceiptDetailReceipt").read(session, (DataObject)receiptObject);
            Double articleDisc = (Double) LM.findLCPByCompoundOldName("discountPercentReceiptSaleDetail").read(session, receiptDetailObject);
            Double articleDiscSum = (Double) LM.findLCPByCompoundOldName("discountSumReceiptDetail").read(session, receiptDetailObject);


            String result = (String)context.requestUserInteraction(new FiscalDatecsDisplayTextClientAction(baudRate, comPort, new ReceiptItem(price, quantity, barcode, name, sum, articleDisc, articleDiscSum, 0, 0)));
            if(result!=null)
                context.requestUserInteraction(new MessageClientAction(result, "Ошибка"));
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }


    }
}
