package equ.clt.handler.aclas;

import com.sun.jna.Library;
import com.sun.jna.Native;
import equ.api.scales.ScalesInfo;
import equ.api.scales.ScalesItemInfo;
import equ.api.scales.TransactionScalesInfo;
import equ.clt.handler.MultithreadScalesHandler;
import lsfusion.base.ExceptionUtils;
import lsfusion.base.Pair;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.support.FileSystemXmlApplicationContext;

import java.io.*;
import java.util.*;

import static equ.clt.handler.HandlerUtils.safeMultiply;
import static equ.clt.handler.HandlerUtils.trim;

public class AclasLS2Handler extends MultithreadScalesHandler {

    private static int pluFile = 0x0000;
    private static int noteFile = 0x000c;
    private static int hotKeyFile = 0x0003;

    protected FileSystemXmlApplicationContext springContext;

    public AclasLS2Handler(FileSystemXmlApplicationContext springContext) {
        this.springContext = springContext;
    }

    protected String getLogPrefix() {
        return "Aclas LS-2: ";
    }

    private boolean init(String libraryDir) {
        if(libraryDir != null) {
            setLibraryPath(libraryDir, "jna.library.path");
            setLibraryPath(libraryDir, "java.library.path");
            return AclasSDK.init();
        } else throw new RuntimeException("No libraryDir found in settings");
    }

    protected void setLibraryPath(String path, String property) {
        String libraryPath = System.getProperty(property);
        if (libraryPath == null) {
            System.setProperty(property, path);
        } else if (!libraryPath.contains(path)) {
            System.setProperty(property, path + ";" + libraryPath);
        }
    }

    private void release() {
        AclasSDK.release();
    }

    private int clearData(ScalesInfo scales) throws IOException {
        File clearFile = File.createTempFile("aclas", ".txt");
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(clearFile), "cp1251"));
            bw.close();

            int result = AclasSDK.clearData(scales.port, clearFile.getAbsolutePath(), pluFile);
            if(result == 0) {
                result = AclasSDK.clearData(scales.port, clearFile.getAbsolutePath(), noteFile);
            }
            //не работает, возвращает ошибку 1. Если реально понадобится очищать PLU, будем засылать файл с нулевыми ButtonValue
            //if(result == 0) {
            //    result = AclasSDK.clearData(scales.port, clearFile.getAbsolutePath(), hotKeyFile);
            //}
            return result;
        } finally {
            safeDelete(clearFile);
        }
    }

    private int loadData(ScalesInfo scales, TransactionScalesInfo transaction) throws IOException {
        int result = loadPLU(scales, transaction);
        if(result == 0) {
            result = loadNote(scales, transaction);
        }
        if(result == 0) {
            result = loadHotKey(scales, transaction);
        }
        return result;
    }

    private int loadPLU(ScalesInfo scales, TransactionScalesInfo transaction) throws IOException {
        File file = File.createTempFile("aclas", ".txt");
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "cp1251"));
            bw.write(StringUtils.join(Arrays.asList("ID", "ItemCode", "DepartmentID", "Name1", "Price",
                    "UnitID", "BarcodeType1", "FreshnessDate", "ValidDate", "PackageType", "Flag1", "Flag2", "IceValue").iterator(), "\t"));

            String barcodePrefix = scales.weightCodeGroupScales != null ? scales.weightCodeGroupScales : "22";
            for (ScalesItemInfo item : transaction.itemsList) {
                bw.write(0x0d);
                bw.write(0x0a);
                boolean isWeight = isWeight(item);
                String name1 = escape(trim(item.name, "", 40));
                String price = String.valueOf((double) safeMultiply(item.price, 100).intValue() / 100).replace(",", ".");
                String unitID = isWeight ? "4" : "10";
                String freshnessDate = item.hoursExpiry != null ? String.valueOf(item.hoursExpiry) : "0";
                String packageType = isWeight ? "0" : "2";
                String iceValue = item.extraPercent != null ? String.valueOf(safeMultiply(item.extraPercent, 10).intValue()) : "0";

                bw.write(StringUtils.join(Arrays.asList(item.idBarcode, item.idBarcode, barcodePrefix, name1, price,
                        unitID, "7", freshnessDate, freshnessDate, packageType, "60", "240", iceValue).iterator(), "\t"));
            }

            bw.close();

            return AclasSDK.loadData(scales.port, file.getAbsolutePath(), pluFile);
        } finally {
            safeDelete(file);
        }
    }

    private int loadNote(ScalesInfo scales, TransactionScalesInfo transaction) throws IOException {
        File file = File.createTempFile("aclas", ".txt");
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "cp1251"));
            bw.write(StringUtils.join(Arrays.asList("PLUID", "Value").iterator(), "\t"));

            for (ScalesItemInfo item : transaction.itemsList) {
                bw.write(0x0d);
                bw.write(0x0a);
                bw.write(StringUtils.join(Arrays.asList(item.idBarcode, escape(trim(item.description, "", 1000))).iterator(), "\t"));
            }

            bw.close();

            return AclasSDK.loadData(scales.port, file.getAbsolutePath(), noteFile);
        } finally {
            safeDelete(file);
        }
    }

    private int loadHotKey(ScalesInfo scales, TransactionScalesInfo transaction) throws IOException {
        File file = File.createTempFile("aclas", ".txt");
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "cp1251"));
            bw.write(StringUtils.join(Arrays.asList("ButtonIndex", "ButtonValue").iterator(), "\t"));

            for (ScalesItemInfo item : transaction.itemsList) {
                if(item.pluNumber != null) {
                    bw.write(0x0d);
                    bw.write(0x0a);
                    bw.write(StringUtils.join(Arrays.asList(item.pluNumber, item.idBarcode).iterator(), "\t"));
                }
            }

            bw.close();

            return AclasSDK.loadData(scales.port, file.getAbsolutePath(), hotKeyFile);
        } finally {
            safeDelete(file);
        }
    }

    private String escape(String value) {
        return value.replace("\t", "{$09}").replace("\n", "{$0A}").replace("\r", "{$0D}");
    }

    private String getErrorDescription(int error) {
        switch (error) {
            case 0:
                return null;
            case 1:
                return "Progress event";
            case 2:
                return "Manual stop";
            case 256:
                return "Initialized";
            case 257:
                return "Uninitialized";
            case 258:
                return "Device doesn't exist";
            case 259:
                return "Unsupported protocol type";
            case 260:
                return "This data type doesn't support this operation";
            case 261:
                return "Not support this data type";
            case 264:
                return "Unable to open input file";
            case 265:
                return "The number of fields doesn't match the number of content";
            case 266:
                return "Communication data exception";
            case 267:
                return "Parsing data exception";
            case 268:
                return "Code page error";
            case 269:
                return "Unable to create output file";
            default:
                return "error " + error;
        }
    }

    @Override
    protected SendTransactionTask getTransactionTask(TransactionScalesInfo transaction, ScalesInfo scales) {
        AclasLS2Settings aclasLS2Settings = springContext.containsBean("aclasLS2Settings") ? (AclasLS2Settings) springContext.getBean("aclasLS2Settings") : null;
        String libraryDir = aclasLS2Settings == null ? null : aclasLS2Settings.getLibraryDir();
        return new AClasLS2SendTransactionTask(transaction, scales, libraryDir);
    }

    class AClasLS2SendTransactionTask extends SendTransactionTask {
        String libraryDir;

        public AClasLS2SendTransactionTask(TransactionScalesInfo transaction, ScalesInfo scales, String libraryDir) {
            super(transaction, scales);
            this.libraryDir = libraryDir;
        }

        @Override
        protected Pair<List<String>, Boolean> run() {
            String error;
            boolean cleared = false;
            try {
                if (init(libraryDir)) {
                    int result = 0;
                    boolean needToClear = !transaction.itemsList.isEmpty() && transaction.snapshot && !scales.cleared;
                    if (needToClear) {
                        result = clearData(scales);
                        cleared = result == 0;
                    }

                    if (result == 0) {
                        processTransactionLogger.info(getLogPrefix() + "Sending " + transaction.itemsList.size() + " items..." + scales.port);
                        result = loadData(scales, transaction);
                    }
                    error = getErrorDescription(result);
                } else {
                    error = getLogPrefix() + "Failed to init";
                }
                if(error != null) {
                    processTransactionLogger.error(error);
                }
            } catch (Throwable t) {
                error = String.format(getLogPrefix() + "IP %s error, transaction %s: %s", scales.port, transaction.id, ExceptionUtils.getStackTraceString(t));
                processTransactionLogger.error(error, t);
            } finally {
                processTransactionLogger.info(getLogPrefix() + "Finally disconnecting..." + scales.port);
                release();
            }
            processTransactionLogger.info(getLogPrefix() + "Completed ip: " + scales.port);
            return Pair.create(error != null ? Collections.singletonList(error) : new ArrayList<>(), cleared);
        }
    }

    public static class AclasSDK {

        public interface AclasSDKLibrary extends Library {

            AclasSDKLibrary aclasSDK = (AclasSDKLibrary) Native.loadLibrary("aclassdk", AclasSDKLibrary.class, getOptions());

            boolean AclasSDK_Initialize(Integer pointer);

            void AclasSDK_Finalize();

            Integer AclasSDK_Sync_ExecTaskA_PB(byte[] addr, Integer port, Integer protocolType, Integer procType, Integer dataType, byte[] fileName);

        }

        public static Map<String, Object> getOptions() {
            Map<String, Object> options = new HashMap<>();
            options.put(Library.OPTION_ALLOW_OBJECTS, Boolean.TRUE);
            return options;
        }

        public static boolean init() {
            return AclasSDKLibrary.aclasSDK.AclasSDK_Initialize(null);
        }

        public static void release() {
            AclasSDKLibrary.aclasSDK.AclasSDK_Finalize();
        }

        public static int clearData(String ip, String filePath, Integer dataType) throws UnsupportedEncodingException {
            return AclasSDKLibrary.aclasSDK.AclasSDK_Sync_ExecTaskA_PB(getBytes(ip), 0, 0, 3, dataType, getBytes(filePath));
        }

        public static int loadData(String ip, String filePath, Integer dataType) throws UnsupportedEncodingException {
            return AclasSDKLibrary.aclasSDK.AclasSDK_Sync_ExecTaskA_PB(getBytes(ip), 0, 0, 0, dataType, getBytes(filePath));
        }

        private static byte[] getBytes(String value) throws UnsupportedEncodingException {
            return (value + "\0").getBytes("cp1251");
        }
    }
}