package lsfusion.erp.region.by.integration.excel;

import jxl.write.WriteException;
import lsfusion.base.RawFileData;
import lsfusion.interop.action.ExportFileClientAction;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class CreateExcelTemplateAllActionProperty extends ScriptingActionProperty {

    public CreateExcelTemplateAllActionProperty(ScriptingLogicsModule LM) {
        super(LM);
    }

    @Override
    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {
        try {

            Map<String, RawFileData> files = new HashMap<>();
            files.putAll(new CreateExcelTemplateUOMsActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateItemsActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateGroupItemsActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateBanksActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateLegalEntitiesActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateStoresActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateDepartmentStoresActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateWarehousesActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateContractsActionProperty(LM).createFile());
            files.putAll(new CreateExcelTemplateUserInvoicesActionProperty(LM).createFile());

            context.delayUserInterfaction(new ExportFileClientAction(files));

        } catch (IOException | WriteException e) {
            throw new RuntimeException(e);
        }
    }
}