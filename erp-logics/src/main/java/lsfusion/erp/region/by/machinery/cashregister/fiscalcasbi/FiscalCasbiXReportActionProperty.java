package lsfusion.erp.region.by.machinery.cashregister.fiscalcasbi;

import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;

import java.sql.SQLException;

public class FiscalCasbiXReportActionProperty extends ScriptingActionProperty {

    public FiscalCasbiXReportActionProperty(ScriptingLogicsModule LM) {
        super(LM, new ValueClass[]{});
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) {
        try {
            Integer comPort = (Integer) LM.findLCPByCompoundOldName("comPortCurrentCashRegister").read(context.getSession());
            Integer baudRate = (Integer) LM.findLCPByCompoundOldName("baudRateCurrentCashRegister").read(context.getSession());

            String result = (String) context.requestUserInteraction(new FiscalCasbiCustomOperationClientAction(1, comPort, baudRate));
            if (result == null) {
                context.apply();
            }
            else
                context.requestUserInteraction(new MessageClientAction(result, "Ошибка"));
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }
}
