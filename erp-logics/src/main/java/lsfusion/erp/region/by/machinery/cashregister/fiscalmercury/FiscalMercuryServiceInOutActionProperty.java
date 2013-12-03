package lsfusion.erp.region.by.machinery.cashregister.fiscalmercury;

import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Iterator;

public class FiscalMercuryServiceInOutActionProperty extends ScriptingActionProperty {
    private final ClassPropertyInterface cashOperationInterface;

    public FiscalMercuryServiceInOutActionProperty(ScriptingLogicsModule LM) throws ScriptingErrorLog.SemanticErrorException {
        super(LM, new ValueClass[]{LM.findClassByCompoundName("CashOperation")});

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        cashOperationInterface = i.next();
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) {
        try {
            DataObject cashOperationObject = context.getDataKeyValue(cashOperationInterface);

            Boolean isDone = LM.findLCPByCompoundOldName("isCompleteCashOperation").read(context.getSession(), cashOperationObject) != null;
            BigDecimal sum = (BigDecimal)LM.findLCPByCompoundOldName("sumCashOperation").read(context.getSession(), cashOperationObject);

            if (!isDone) {
                String result = (String) context.requestUserInteraction(new FiscalMercuryServiceInOutClientAction(sum));
                if (result == null){
                    LM.findLCPByCompoundOldName("isCompleteCashOperation").change(true, context.getSession(), cashOperationObject);
                }
                else
                    context.requestUserInteraction(new MessageClientAction(result, "Ошибка"));
            }

        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }
}
