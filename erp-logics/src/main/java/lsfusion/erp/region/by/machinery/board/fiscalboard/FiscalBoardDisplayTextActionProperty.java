package lsfusion.erp.region.by.machinery.board.fiscalboard;

import com.google.common.base.Throwables;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.language.ScriptingErrorLog;
import lsfusion.server.language.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Iterator;

import static lsfusion.base.BaseUtils.trimToEmpty;

public class FiscalBoardDisplayTextActionProperty extends FiscalBoardActionProperty {
    private final ClassPropertyInterface receiptDetailInterface;

    public FiscalBoardDisplayTextActionProperty(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        receiptDetailInterface = i.next();
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {

        DataSession session = context.getSession();
        DataObject receiptDetailObject = context.getDataKeyValue(receiptDetailInterface);

        try {
            ObjectValue receiptObject = findProperty("receipt[ReceiptDetail]").readClasses(session, receiptDetailObject);
            Integer comPortBoard = (Integer) findProperty("comPortBoardCurrentCashRegister[]").read(context);
            Integer baudRateBoard = (Integer) findProperty("baudRateBoardCurrentCashRegister[]").read(context);
            boolean uppercase = findProperty("uppercaseBoardCurrentCashRegister[]").read(context) != null;

            String name = trimToEmpty((String) findProperty("overNameSku[ReceiptDetail]").read(session, receiptDetailObject));
            BigDecimal quantity = (BigDecimal) findProperty("quantity[ReceiptDetail]").read(session, receiptDetailObject);
            BigDecimal price = (BigDecimal) findProperty("price[ReceiptDetail]").read(session, receiptDetailObject);
            BigDecimal sum = (BigDecimal) findProperty("sumReceiptDetail[Receipt]").read(session, (DataObject) receiptObject);

            String[] lines = generateText(price, quantity == null ? BigDecimal.ZERO : quantity, sum, name);
            context.requestUserInteraction(new FiscalBoardDisplayTextClientAction(lines[0], lines[1], baudRateBoard, comPortBoard, uppercase, null));

        } catch (SQLException | ScriptingErrorLog.SemanticErrorException e) {
            throw Throwables.propagate(e);
        }
    }

    private String[] generateText(BigDecimal price, BigDecimal quantity, BigDecimal sum, String nameItem) {
        String firstLine = " " + toStr(quantity) + "x" + toStr(price);
        firstLine = fillSpaces(nameItem, lineLength - firstLine.length(), true) + firstLine;
        String secondLine = "ИТОГ:" + fillSpaces(toStr(sum), lineLength - 5);
        return new String[]{firstLine, secondLine};
    }
}