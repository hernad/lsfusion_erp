package lsfusion.erp.region.by.ukm;

import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.ServerLoggers;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import org.apache.commons.lang.StringUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;

import static org.apache.commons.lang.StringUtils.trim;

public class SynchronizeItemLoyaActionProperty extends SynchronizeLoyaActionProperty {
    private final ClassPropertyInterface itemInterface;

    public SynchronizeItemLoyaActionProperty(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        itemInterface = i.next();
    }

    @Override
    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {
        try {

            boolean logRequests = findProperty("logRequestsLoya[]").read(context) != null;
            boolean useBarcodeAsId = findProperty("useBarcodeAsIdLoya[]").read(context) != null;
            boolean useMinPrice = findProperty("useMinPrice[]").read(context) != null;

            DataObject itemObject = context.getDataKeyValue(itemInterface);

            SettingsLoya settings = login(context);
            if (settings.error == null) {

                boolean nearestForbidPromotion = findProperty("nearestForbidPromotion[Item]").read(context, itemObject) != null;
                Integer maxDiscount = (Integer) (nearestForbidPromotion ? 0 : findProperty("maxDiscountLoya[]").read(context));
                Integer maxAllowBonus = (Integer) findProperty("maxAllowBonusLoya[]").read(context);
                Integer maxAwardBonus = (Integer) findProperty("maxAwardBonusLoya[]").read(context);

                Map<String, Integer> discountLimits = getDiscountLimits(maxDiscount, maxAllowBonus, maxAwardBonus);
                String idSku = (String) findProperty("id[Item]").read(context, itemObject);
                String barcode = (String) findProperty("idBarcode[Item]").read(context, itemObject);
                String id = useBarcodeAsId ? barcode : idSku;
                String caption = StringUtils.trimToEmpty((String) findProperty("nameAttribute[Item]").read(context, itemObject));
                String idUOM = (String) findProperty("idUOM[Item]").read(context, itemObject);
                boolean split = findProperty("split[Item]").read(context, itemObject) != null;
                String idSkuGroup = trim((String) findProperty("overIdSkuGroup[Item]").read(context, itemObject));
                Integer idLoyaBrand = (Integer) findProperty("idLoyaBrand[Item]").read(context, itemObject);
                Item item = new Item(id, caption, idUOM, split, idSkuGroup, idLoyaBrand);
                String result = uploadItem(context, settings, item, discountLimits, useMinPrice ? readMinPriceLimits(context, itemObject) : null, logRequests);
                findProperty("synchronizeItemResult[]").change(result, context);

            } else {
                findProperty("synchronizeItemResult[]").change(settings.error, context);
                context.delayUserInteraction(new MessageClientAction(settings.error, failCaption));
            }
        } catch (Exception e) {
            ServerLoggers.importLogger.error(failCaption, e);
            context.delayUserInteraction(new MessageClientAction(e.getMessage(), failCaption));
        }
    }

    private List<MinPriceLimit> readMinPriceLimits(ExecutionContext<ClassPropertyInterface> context, DataObject itemObject) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        List<MinPriceLimit> result = new ArrayList<>();
        KeyExpr departmentStoreExpr = new KeyExpr("departmentStore");
        ImRevMap<String, KeyExpr> keys = MapFact.singletonRev("departmentStore", departmentStoreExpr);
        QueryBuilder<String, Object> query = new QueryBuilder<>(keys);
        query.addProperty("idLoyaDepartmentStore", findProperty("idLoya[DepartmentStore]").getExpr(departmentStoreExpr));
        query.addProperty("loyaMinPrice", findProperty("loyaMinPrice[Item, DepartmentStore]").getExpr(context.getModifier(), itemObject.getExpr(), departmentStoreExpr));
        query.and(findProperty("inLoya[DepartmentStore]").getExpr(departmentStoreExpr).getWhere());
        query.and(findProperty("idLoya[DepartmentStore]").getExpr(departmentStoreExpr).getWhere());
        query.and(findProperty("loyaMinPrice[Item, DepartmentStore]").getExpr(context.getModifier(), itemObject.getExpr(), departmentStoreExpr).getWhere());
        ImOrderMap<ImMap<String, DataObject>, ImMap<Object, ObjectValue>> queryResult = query.executeClasses(context);
        for (int i = 0; i < queryResult.size(); i++) {
            ImMap<Object, ObjectValue> valueEntry = queryResult.getValue(i);
            Integer idLoyaDepartmentStore = (Integer) valueEntry.get("idLoyaDepartmentStore").getValue();
            BigDecimal minPrice = (BigDecimal) valueEntry.get("loyaMinPrice").getValue();
            result.add(new MinPriceLimit(idLoyaDepartmentStore, minPrice));
        }
        return result;
    }
}