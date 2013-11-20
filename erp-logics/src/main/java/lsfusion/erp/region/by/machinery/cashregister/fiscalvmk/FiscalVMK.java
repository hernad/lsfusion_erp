package lsfusion.erp.region.by.machinery.cashregister.fiscalvmk;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.ptr.IntByReference;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;

public class FiscalVMK {

    public interface vmkDLL extends Library {

        vmkDLL vmk = (vmkDLL) Native.loadLibrary("vmk", vmkDLL.class);

        Integer vmk_lasterror();

        void vmk_errorstring(Integer error, byte[] buffer, Integer length);

        Boolean vmk_open(String comport, Integer baudrate);

        void vmk_close();

        Boolean vmk_opensmn();

        Boolean vmk_opencheck(Integer type);

        Boolean vmk_cancel();

        Boolean vmk_sale(String coddigit, byte[] codname, Integer codcena, Integer ot, Double quantity,
                         Integer sum);

        Boolean vmk_discount(byte[] name, Integer value, int flag);

        Boolean vmk_subtotal();

        Boolean vmk_prnch(String message);

        Boolean vmk_oplat(Integer type, Integer sum, Integer flagByte);

        Boolean vmk_xotch();

        Boolean vmk_zotch();

        Boolean vmk_feed(int type, int cnt_string, int cnt_dot_line);

        Boolean vmk_vnes(long sum);

        Boolean vmk_vyd(long sum);

        Boolean vmk_opendrawer(int cnt_msek);

        Boolean vmk_indik(byte[] firstLine, byte[] secondLine);

        Boolean vmk_ksastat(ByReference rej, ByReference stat);
    }

    static void init() {

        try {
            System.loadLibrary("msvcr100");
        } catch (UnsatisfiedLinkError e) {
            System.out.println(e.toString());
        }
        try {
            System.loadLibrary("msvcp100");
        } catch (UnsatisfiedLinkError e) {
            System.out.println(e.toString());
        }
        try {
            System.loadLibrary("QtCore4");
        } catch (UnsatisfiedLinkError e) {
            System.out.println(e.toString());
        }
        try {
            System.loadLibrary("QtNetwork4");
        } catch (UnsatisfiedLinkError e) {
            System.out.println(e.toString());
        }
        try {
            System.loadLibrary("vmk");
        } catch (Exception e) {
            System.out.println(e.toString());
        }

        try {
            Thread.sleep(100);
        } catch (Exception e) {
        }
    }

    public static String getError(boolean closePort) {
        Integer lastError = vmkDLL.vmk.vmk_lasterror();
        int length = 255;
        byte[] lastErrorText = new byte[length];
        vmkDLL.vmk.vmk_errorstring(lastError, lastErrorText, length);
        if (closePort)
            closePort();
        return Native.toString(lastErrorText, "cp1251");
    }

    public static void openPort(int comPort, int baudRate) {
        if (!vmkDLL.vmk.vmk_open("COM" + comPort, baudRate))
            checkErrors(true);
    }

    public static void closePort() {
        vmkDLL.vmk.vmk_close();
    }

    public static boolean openReceipt(int type) {    //0 - продажа, 1 - возврат
        return vmkDLL.vmk.vmk_opencheck(type);
    }

    public static boolean cancelReceipt() {
        return vmkDLL.vmk.vmk_cancel();
    }

    public static boolean getFiscalClosureStatus() {
        IntByReference rej = new IntByReference();
        IntByReference stat = new IntByReference();
        if (!vmkDLL.vmk.vmk_ksastat(rej, stat))
            return false;
        if (BigInteger.valueOf(stat.getValue()).testBit(14))
            if (!cancelReceipt())
                return false;
        return true;
    }

    public static boolean printFiscalText(String msg) {
        return vmkDLL.vmk.vmk_prnch(msg);
    }

    public static boolean totalCash(BigDecimal sum) {
        if (sum == null)
            return true;
        return vmkDLL.vmk.vmk_oplat(0, Math.abs(sum.intValue()), 0/*"00000000"*/);
    }

    public static boolean totalCard(BigDecimal sum) {
        if (sum == null)
            return true;
        return vmkDLL.vmk.vmk_oplat(1, Math.abs(sum.intValue()), 0/*"00000000"*/);
    }

    public static void xReport() {
        if (!vmkDLL.vmk.vmk_xotch())
            checkErrors(true);
    }

    public static void zReport() {
        if (!vmkDLL.vmk.vmk_zotch())
            checkErrors(true);
    }

    public static void advancePaper(int lines) {
        if (!vmkDLL.vmk.vmk_feed(1, lines, 1))
            checkErrors(true);
    }

    public static boolean inOut(Long sum) {

        if (sum > 0) {
            if (!vmkDLL.vmk.vmk_vnes(sum))
                checkErrors(true);
        } else {
            if (!vmkDLL.vmk.vmk_vyd(-sum))
                return false;
        }
        return true;
    }

    public static boolean openDrawer() {
        return vmkDLL.vmk.vmk_opendrawer(0);
    }

    public static void displayText(ReceiptItem item) {
        try {
            String firstLine = " " + toStr(item.quantity) + "x" + String.valueOf(item.price);
            firstLine = item.name.substring(0, 16 - Math.min(16, firstLine.length())) + firstLine;
            String secondLine = String.valueOf(item.sumPos);
            while (secondLine.length() < 11)
                secondLine = " " + secondLine;
            secondLine = "ИТОГ:" + secondLine;
            if (!vmkDLL.vmk.vmk_indik((firstLine + "\0").getBytes("cp1251"), (new String(secondLine + "\0")).getBytes("cp1251")))
                checkErrors(true);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    public static boolean registerItem(ReceiptItem item) {
        try {
            return vmkDLL.vmk.vmk_sale(item.barcode, (item.name + "\0").getBytes("cp1251"), (int) Math.abs(item.price), 1 /*отдел*/, item.quantity, 0);
        } catch (UnsupportedEncodingException e) {
            return false;
        }
    }

    public static boolean discountItem(ReceiptItem item) {
        if (item.articleDiscSum == 0)
            return true;
        boolean discount = item.articleDiscSum < 0;
        try {
            return vmkDLL.vmk.vmk_discount(((discount ? "Скидка" : "Наценка") + "\0").getBytes("cp1251"), (int) Math.abs(item.articleDiscSum), discount ? 3 : 1);
        } catch (UnsupportedEncodingException e) {
            return false;
        }
    }

    public static boolean subtotal() {
        if (!vmkDLL.vmk.vmk_subtotal())
            return false;
        return true;
    }

    public static void opensmIfClose() {
        IntByReference rej = new IntByReference();
        IntByReference stat = new IntByReference();
        if (!vmkDLL.vmk.vmk_ksastat(rej, stat))
            checkErrors(true);
        if (!BigInteger.valueOf(stat.getValue()).testBit(15))//15 - открыта ли смена
            if (!vmkDLL.vmk.vmk_opensmn())
                checkErrors(true);
    }

    private static String toStr(double value) {
        boolean isInt = (value - (int) value) == 0;
        return isInt ? String.valueOf((int) value) : String.valueOf(value);
    }

    public static int checkErrors(Boolean throwException) {
        Integer lastError = vmkDLL.vmk.vmk_lasterror();
        if (lastError != 0) {
            if (throwException)
                throw new RuntimeException("VMK Exception: " + lastError);
        }
        return lastError;
    }
}

