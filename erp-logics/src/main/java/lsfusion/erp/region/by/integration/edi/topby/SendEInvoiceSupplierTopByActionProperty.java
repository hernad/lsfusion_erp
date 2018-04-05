package lsfusion.erp.region.by.integration.edi.topby;

import lsfusion.erp.region.by.integration.edi.SendEInvoiceSupplierActionProperty;
import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.ServerLoggers;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;

import java.sql.SQLException;

public class SendEInvoiceSupplierTopByActionProperty extends SendEInvoiceSupplierActionProperty {
    String provider = "TopBy";

    public SendEInvoiceSupplierTopByActionProperty(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);
    }

    @Override
    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {
        try {
            String login = (String) findProperty("loginInvoiceTopBy[]").read(context);
            String password = (String) findProperty("passwordInvoiceTopBy[]").read(context);
            String host = (String) findProperty("hostInvoiceTopBy[]").read(context);
            Integer port = (Integer) findProperty("portInvoiceTopBy[]").read(context);
            String outputDir = (String) findProperty("outputDirTopBy[]").read(context);

            String aliasEDSService = (String) findProperty("aliasEDSServiceTopBy[]").read(context);
            String passwordEDSService = (String) findProperty("passwordEDSServiceTopBy[]").read(context);
            String hostEDSService = (String) findProperty("hostEDSServiceTopBy[]").read(context);
            Integer portEDSService = (Integer) findProperty("portEDSServiceTopBy[]").read(context);

            if (login != null && password != null && host != null && port != null) {
                String url = String.format("http://%s:%s/DmcService", host, port);
                sendEInvoice(context, url, login, password, host, port, provider, outputDir, hostEDSService, portEDSService, aliasEDSService, passwordEDSService);
            } else {
                ServerLoggers.importLogger.info(provider + " SendEInvoice: не заданы имя пользователя / пароль / хост / порт");
                context.delayUserInteraction(new MessageClientAction(provider + " Заказ не выгружен: не заданы имя пользователя / пароль / хост / порт", "Экспорт"));
            }

        } catch (ScriptingErrorLog.SemanticErrorException e) {
            ServerLoggers.importLogger.error(provider + " error: ", e);
            context.delayUserInteraction(new MessageClientAction(provider + " error: " + e.getMessage(), "Ошибка"));
        }
    }
}