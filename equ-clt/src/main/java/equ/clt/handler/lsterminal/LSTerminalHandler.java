package equ.clt.handler.lsterminal;

import com.google.common.base.Throwables;
import equ.api.SoftCheckInfo;
import equ.api.TransactionInfo;
import equ.api.terminal.*;
import equ.clt.EquipmentServer;
import org.apache.log4j.Logger;
import org.springframework.util.FileCopyUtils;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.sql.*;
import java.util.*;

public class LSTerminalHandler extends TerminalHandler {

    protected final static Logger logger = Logger.getLogger(EquipmentServer.class);

    String dbPath = "\\db";

    public LSTerminalHandler() {
    }

    @Override
    public void sendTransaction(TransactionInfo transactionInfo, List machineryInfoList) throws IOException {
        try {
            Integer nppGroupTerminal = ((TransactionTerminalInfo) transactionInfo).nppGroupTerminal;
            String directory = ((TransactionTerminalInfo) transactionInfo).directoryGroupTerminal;
            if (directory != null) {
                String exchangeDirectory = directory + "\\import";
                if ((new File(exchangeDirectory).exists() || new File(exchangeDirectory).mkdir())) {
                    //copy base to exchange directory
                    FileCopyUtils.copy(new File(makeDBPath(dbPath, nppGroupTerminal)), new File(makeDBPath(exchangeDirectory, nppGroupTerminal)));
                }
            }
        } catch (Exception e) {
            logger.error(e);
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void sendSoftCheck(SoftCheckInfo softCheckInfo) throws IOException {
    }

    @Override
    public void saveTransactionTerminalInfo(TransactionTerminalInfo transactionInfo) throws IOException {

        logger.info("LSTerminal: save Transaction #" + transactionInfo.id);

        Integer nppGroupTerminal = transactionInfo.nppGroupTerminal;
        File directory = new File(dbPath);
        if (directory.exists() || directory.mkdir()) {
            try {
                Class.forName("org.sqlite.JDBC");
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + makeDBPath(dbPath, nppGroupTerminal));

                createGoodsTableIfNotExists(connection);
                updateGoodsTable(connection, transactionInfo);

                createAssortTableIfNotExists(connection);
                updateAssortTable(connection, transactionInfo);

                createVANTableIfNotExists(connection);
                updateVANTable(connection, transactionInfo);

                createANATableIfNotExists(connection);
                updateANATable(connection, transactionInfo);

                createOrderTableIfNotExists(connection);
                updateOrderTable(connection, transactionInfo);

                connection.close();

            } catch (Exception e) {
                logger.error(e);
                throw Throwables.propagate(e);
            }
        } else {
            logger.error("Directory " + directory.getAbsolutePath() + "doesn't exist");
        }
    }

    public void finishReadingTerminalDocumentInfo(TerminalDocumentBatch terminalDocumentBatch) {
        logger.info("LSTerminal: Finish Reading started");
        for (String readFile : terminalDocumentBatch.readFiles) {
            File f = new File(readFile);
            if (f.delete()) {
                logger.info("LSTerminal: file " + readFile + " has been deleted");
            } else {
                throw new RuntimeException("The file " + f.getAbsolutePath() + " can not be deleted");
            }
        }
    }

    @Override
    public TerminalDocumentBatch readTerminalDocumentInfo(List machineryInfoList) throws IOException {

        try {

            Class.forName("org.sqlite.JDBC");
            
            Set<String> directorySet = new HashSet<String>();
            for (Object m : machineryInfoList) {
                TerminalInfo t = (TerminalInfo) m;
                if (t.directory != null)
                    directorySet.add(t.directory);
            }

            List<String> filePathList = new ArrayList<String>();

            List<TerminalDocumentDetail> terminalDocumentDetailList = new ArrayList<TerminalDocumentDetail>();

            for (String directory : directorySet) {

                String exchangeDirectory = directory + "\\export";

                File[] filesList = new File(exchangeDirectory).listFiles(new FileFilter() {
                    @Override
                    public boolean accept(File pathname) {
                        return pathname.getName().toUpperCase().startsWith("DOK_") && pathname.getPath().toUpperCase().endsWith(".DB");
                    }
                });

                if (filesList == null || filesList.length == 0)
                    logger.info("LSTerminal: No terminal documents found in " + exchangeDirectory);
                else {
                    logger.info("LSTerminal: found " + filesList.length + " file(s) in " + exchangeDirectory);

                    for (File file : filesList) {
                        try {
                            String fileName = file.getName();
                            logger.info("LSTerminal: reading " + fileName);
                            if (isFileLocked(file)) {
                                logger.info("LSTerminal: " + fileName + " is locked");
                            } else {

                                Map<String, Integer> barcodeCountMap = new HashMap<String, Integer>();
                                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                                List<List<Object>> dokData = readDokFile(connection);

                                for (List<Object> entry : dokData) {
                                
                                    String idTerminalDocumentType = (String) entry.get(0); //VOP
                                    String numberTerminalDocument = (String) entry.get(1); //NUM
                                    String idTerminalHandbookType1 = (String) entry.get(2); //ANA1
                                    String idTerminalHandbookType2 = (String) entry.get(3); //ANA2
                                    String barcode = (String) entry.get(4); //BARCODE
                                    BigDecimal quantity = (BigDecimal) entry.get(5); //QUANT
                                    BigDecimal price = (BigDecimal) entry.get(6); //PRICE
                                    BigDecimal sum = safeMultiply(quantity, price);
                                    Integer count = barcodeCountMap.get(barcode);
                                    String idTerminalDocumentDetail = numberTerminalDocument + "_" + barcode + (count == null ? "" : ("_" + count));
                                    barcodeCountMap.put(barcode, count == null ? 1 : (count + 1));

                                    terminalDocumentDetailList.add(new TerminalDocumentDetail(numberTerminalDocument, idTerminalHandbookType1,
                                            idTerminalHandbookType2, idTerminalDocumentType, idTerminalDocumentDetail, barcode, price, quantity, sum));
                                }

                                connection.close();
                                filePathList.add(file.getAbsolutePath());
                            }
                        } catch (Throwable e) {
                            logger.error("File: " + file.getAbsolutePath(), e);
                        }
                    }
                }
            }

            return new TerminalDocumentBatch(terminalDocumentDetailList, filePathList);
        } catch (Exception e) {
            logger.error(e);
            throw Throwables.propagate(e);
        }
    }

    private List<List<Object>> readDokFile(Connection connection) throws SQLException {

        List<List<Object>> itemsList = new ArrayList<List<Object>>();

        String vop = null;
        String num = null;
        String ana1 = null;
        String ana2 = null;
        
        Statement statement = connection.createStatement();
        String sql = "SELECT vop, num, ana1, ana2 FROM dok LIMIT 1;";
        ResultSet resultSet = statement.executeQuery(sql);
        if (resultSet.next()) {
            vop = resultSet.getString("vop");
            num = resultSet.getString("num");
            ana1 = resultSet.getString("ana1");
            ana2 = resultSet.getString("ana2");
        }
        resultSet.close();
        statement.close();

        statement = connection.createStatement();
        sql = "SELECT barcode, quant, price FROM pos;";
        resultSet = statement.executeQuery(sql);
        while (resultSet.next()) {
            String barcode = resultSet.getString("barcode");
            BigDecimal quantity = new BigDecimal(resultSet.getDouble("quant"));
            BigDecimal price = new BigDecimal(resultSet.getDouble("price"));
            itemsList.add(Arrays.asList((Object) vop, num, ana1, ana2, barcode, quantity, price));
        }
        resultSet.close();
        statement.close();
              
        return itemsList;
    }

    private void createANATableIfNotExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        String sql = "CREATE TABLE IF NOT EXISTS ana " +
                "(ana  TEXT PRIMARY KEY," +
                " naim TEXT," +
                " fld1 TEXT," +
                " fld2 TEXT," +
                " fld3 TEXT)";
        statement.executeUpdate(sql);
        statement.close();
    }

    private void updateANATable(Connection connection, TransactionTerminalInfo transactionInfo) throws SQLException {
        if (transactionInfo.terminalDocumentTypeList != null && !transactionInfo.terminalDocumentTypeList.isEmpty()) {
            Statement statement = connection.createStatement();
            String sql = "BEGIN TRANSACTION;";
            for (TerminalDocumentType terminalDocumentType : transactionInfo.terminalDocumentTypeList) {
                if (terminalDocumentType.id != null)
                    sql += String.format("INSERT OR REPLACE INTO ana VALUES('%s', '%s', '%s', '%s', '%s');",
                            terminalDocumentType.id, formatValue(terminalDocumentType.name), "", "", "");
            }
            sql += "COMMIT;";
            statement.executeUpdate(sql);
            statement.close();
        }
    }

    private void createAssortTableIfNotExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        String sql = "CREATE TABLE IF NOT EXISTS assort " +
                "(post    TEXT," +
                " barcode TEXT," +
                "PRIMARY KEY ( post, barcode))";
        statement.executeUpdate(sql);
        statement.close();
    }

    private void updateAssortTable(Connection connection, TransactionTerminalInfo transactionInfo) throws SQLException {
        if (transactionInfo.itemsList != null && !transactionInfo.itemsList.isEmpty()) {
            Statement statement = connection.createStatement();
            String sql = "BEGIN TRANSACTION;";
            for (TerminalItemInfo item : transactionInfo.itemsList) {
                if (/*item.idSupplier != null && */item.idBarcode != null)
                    sql += String.format("INSERT OR REPLACE INTO assort VALUES(%s, %s);",
                            "1"/*item.idSupplier*/, item.idBarcode);
            }
            sql += "COMMIT;";
            statement.executeUpdate(sql);
            statement.close();
        }
    }

    private void createGoodsTableIfNotExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        String sql = "CREATE TABLE IF NOT EXISTS goods " +
                "(barcode TEXT PRIMARY KEY," +
                " naim    TEXT," +
                " price   REAL," +
                " quant   REAL," +
                " fld1    TEXT," +
                " fld2    TEXT," +
                " fld3    TEXT," +
                " fld4    TEXT," +
                " fld5    TEXT," +
                " image   TEXT)";
        statement.executeUpdate(sql);
        statement.close();
    }

    private void updateGoodsTable(Connection connection, TransactionTerminalInfo transactionInfo) throws SQLException {
        if (transactionInfo.itemsList != null && !transactionInfo.itemsList.isEmpty()) {
            Statement statement = connection.createStatement();
            String sql = "BEGIN TRANSACTION;";
            for (TerminalItemInfo item : transactionInfo.itemsList) {
                if (item.idBarcode != null)
                    sql += String.format("INSERT OR REPLACE INTO goods VALUES('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s');",
                            formatValue(item.idBarcode), formatValue(item.name), formatValue(item.price),
                            formatValue(item.quantity), "", "", "", "", "", formatValue(item.image));
            }

            //чит: добавляем товары из заказов, но только те, которых ещё нет в базе
            for (TerminalOrder order : transactionInfo.terminalOrderList) {
                if (order.barcode != null)
                    sql += String.format("INSERT OR IGNORE INTO goods VALUES('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s');",
                            formatValue(order.barcode), formatValue(order.name), formatValue(order.price), "", "", "", "", "", "", "");
            }

            sql += "COMMIT;";
            statement.executeUpdate(sql);
            statement.close();
        }
    }

    private void createVANTableIfNotExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        String sql = "CREATE TABLE IF NOT EXISTS van " +
                "(van    TEXT PRIMARY KEY," +
                " naim   TEXT)";
        statement.executeUpdate(sql);
        statement.close();
    }

    private void updateVANTable(Connection connection, TransactionTerminalInfo transactionInfo) throws SQLException {
        if (transactionInfo.terminalHandbookTypeList != null && !transactionInfo.terminalHandbookTypeList.isEmpty()) {
            Statement statement = connection.createStatement();
            String sql = "BEGIN TRANSACTION;";
            for (TerminalHandbookType terminalHandbookType : transactionInfo.terminalHandbookTypeList) {
                if (terminalHandbookType.id != null && terminalHandbookType.name != null)
                    sql += String.format("INSERT OR REPLACE INTO van VALUES('%s', '%s');",
                            terminalHandbookType.id, terminalHandbookType.name);
            }
            sql += "COMMIT;";
            statement.executeUpdate(sql);
            statement.close();
        }
    }

    private void createOrderTableIfNotExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        String sql = "CREATE TABLE IF NOT EXISTS zayavki " +
                "(dv     TEXT," +
                " num   TEXT," +
                " post  TEXT," +
                " barcode   TEXT," +
                " quant   REAL," +
                " price    REAL," +
                "PRIMARY KEY (num, barcode))";
        statement.executeUpdate(sql);
        statement.close();
    }

    private void updateOrderTable(Connection connection, TransactionTerminalInfo transactionInfo) throws SQLException {
        if (transactionInfo.terminalOrderList != null && !transactionInfo.terminalOrderList.isEmpty()) {
            Statement statement = connection.createStatement();
            String sql = "BEGIN TRANSACTION;";
            for (TerminalOrder order : transactionInfo.terminalOrderList) {
                if (order.number != null)
                    sql += String.format("INSERT OR REPLACE INTO zayavki VALUES('%s', '%s', '%s', '%s', '%s', '%s');",
                            formatValue(order.date), formatValue(order.number), formatValue(order.supplier),
                            formatValue(order.barcode), formatValue(order.quantity), formatValue(order.price));
            }
            sql += "COMMIT;";
            statement.executeUpdate(sql);
            statement.close();
        }
    }

    private Object formatValue(Object value) {
        return value == null ? "" : value;
    }

    private String makeDBPath(String directory, Integer nppGroupTerminal) {
        return directory + "\\" + nppGroupTerminal + ".db";
    }

    public static boolean isFileLocked(File file) {
        boolean isLocked = false;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = new RandomAccessFile(file, "rw").getChannel();
            lock = channel.tryLock();
            if (lock == null)
                isLocked = true;
        } catch (Exception e) {
            logger.info(e);
            isLocked = true;
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (Exception e) {
                    logger.info(e);
                    isLocked = true;
                }
            }
            if (channel != null)
                try {
                    channel.close();
                } catch (IOException e) {
                    logger.info(e);
                    isLocked = true;
                }
        }
        return isLocked;
    }

    protected BigDecimal safeMultiply(BigDecimal operand1, BigDecimal operand2) {
        if (operand1 == null || operand1.doubleValue() == 0 || operand2 == null || operand2.doubleValue() == 0)
            return null;
        else return operand1.multiply(operand2);
    }
}