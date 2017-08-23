package lsfusion.erp.region.by.machinery.board.shtrih;

import lsfusion.erp.region.by.machinery.board.BoardDaemon;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.lifecycle.LifecycleEvent;
import lsfusion.server.logics.*;
import lsfusion.server.logics.scripted.ScriptingBusinessLogics;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;
import org.springframework.util.Assert;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

public class ShtrihBoardDaemon extends BoardDaemon {

    private ScriptingLogicsModule LM;
    private static String charset = "cp1251";
    private Map<InetAddress, String> ipMap = new HashMap<>();
    //передаётся 2 строки по 30 символов, но показывается только по 22
    private int textLength = 44;
    private int lineLength = 30;
    private int gapLength = 8;

    public ShtrihBoardDaemon(ScriptingBusinessLogics businessLogics, DBManager dbManager, LogicsInstance logicsInstance) {
        super(businessLogics, dbManager, logicsInstance);
    }

    @Override
    protected void onInit(LifecycleEvent event) {
        LM = logicsInstance.getBusinessLogics().getModule("ShtrihBoard");
        Assert.notNull(LM, "can't find ShtrihBoard module");
    }

    @Override
    protected void onStarted(LifecycleEvent event) {
        startLogger.info("Starting " + getEventName() + " Daemon");
        try (DataSession session = dbManager.createSession()) {
            String host = (String) LM.findProperty("hostShtrihBoard[]").read(session);
            Integer port = (Integer) LM.findProperty("portShtrihBoard[]").read(session);
            setupDaemon(dbManager, host, port != null ? port : 2004);
        } catch (SQLException | ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
            throw new RuntimeException("Error starting " + getEventName() + " Daemon: ", e);
        }
    }

    @Override
    public String getEventName() {
        return "shtrih-board";
    }

    @Override
    protected Callable getCallable(Socket socket) {
        return new SocketCallable(socket);
    }

    public class SocketCallable implements Callable {

        private Socket socket;

        public SocketCallable(Socket socket) {
            this.socket = socket;
        }

        @Override
        public Object call() {

            DataInputStream inFromClient = null;
            DataOutputStream outToClient = null;

            try {

                inFromClient = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                outToClient = new DataOutputStream(socket.getOutputStream());
                String barcode = inFromClient.readLine();

                if (barcode != null) {

                    //getHostName is slow operation, so we use map
                    InetAddress inetAddress = socket.getInetAddress();
                    String ip = ipMap.get(inetAddress);
                    if (ip == null) {
                        ip = inetAddress.getHostName();
                        ipMap.put(inetAddress, ip);
                    }
                    barcode = barcode.length() > 2 ? barcode.substring(2) : barcode;
                    byte[] message = readMessage(barcode, ip);
                    outToClient.write(message);
                    terminalLogger.info(String.format(getEventName() + " succeeded request ip %s, barcode %s, reply %s", ip, barcode, new String(message, 3, message.length - 3, charset)));

                }
                Thread.sleep(1000);
                return null;
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                terminalLogger.error(getEventName() + " error: ", e);
            } finally {
                try {
                    if (outToClient != null)
                        outToClient.close();
                    if (inFromClient != null)
                        inFromClient.close();
                } catch (IOException e) {
                    terminalLogger.error(getEventName() + " error occured: ", e);
                }
            }
            return null;
        }

        private byte[] readMessage(String idBarcode, String ip) throws SQLException, UnsupportedEncodingException, SQLHandledException, ScriptingErrorLog.SemanticErrorException {
            byte[] messageBytes = null;
            int length = 60;
            try (DataSession session = dbManager.createSession()) {

                String weightPrefix = (String) LM.findProperty("weightPrefixIP").read(session, new DataObject(ip));
                String piecePrefix = (String) LM.findProperty("piecePrefixIP").read(session, new DataObject(ip));
                if (weightPrefix != null && idBarcode.length() == 13 && (idBarcode.startsWith(weightPrefix) || idBarcode.startsWith(piecePrefix)))
                    idBarcode = idBarcode.substring(2, 7);
                ObjectValue stockObject = LM.findProperty("stockIP[VARSTRING[100]]").readClasses(session, new DataObject(ip));
                ObjectValue skuObject = LM.findProperty("skuBarcode[VARSTRING[15]]").readClasses(session, new DataObject(idBarcode));

                String errorLine1 = null;
                String errorLine2 = null;
                if(skuObject instanceof NullValue)
                    errorLine1 = "Штрихкод не найден";
                if(stockObject instanceof NullValue) {
                    errorLine1 = "Неверные параметры";
                    errorLine2 = "сервера";
                }

                if (errorLine1 == null) {
                    String captionItem = (String) LM.findProperty("name[Item]").read(session, skuObject);

                    BigDecimal price = (BigDecimal) LM.findProperty("transactionPrice[Sku,Stock]").read(session, skuObject, stockObject);
                    if (price == null || price.equals(BigDecimal.ZERO)) {
                        errorLine1 = "Штрихкод не найден";
                    } else {
                        messageBytes = getPriceBytes(captionItem, price);
                    }
                }
                if(messageBytes == null)
                    messageBytes = getErrorBytes(errorLine1, errorLine2);
                byte[] answer = new byte[length + 3];
                answer[0] = (byte) 0xAB; //command
                answer[1] = 0; //error code
                answer[2] = (byte) length; //message length
                System.arraycopy(messageBytes, 0, answer, 3, messageBytes.length);
                return answer;
            }
        }

        private byte[] getPriceBytes(String caption, BigDecimal price) throws UnsupportedEncodingException {
            String priceMessage = formatPrice(price);
            String message = appendSpaces(caption, textLength - priceMessage.length() - 1) + " " + priceMessage;
            String gap = appendSpaces("", gapLength);
            return (message.substring(0, textLength / 2) + gap + message.substring(textLength / 2, textLength) + gap).getBytes(charset);
        }

        private byte[] getErrorBytes(String errorLine1, String errorLine2) throws UnsupportedEncodingException {
            return (appendSpaces(errorLine1, lineLength) + appendSpaces(errorLine2, lineLength)).getBytes(charset);
        }

        private String appendSpaces(String line, int length) {
            if(line == null)
                line = "";
            StringBuilder lineBuilder = new StringBuilder(line.substring(0, Math.min(line.length(), length)));
            while(lineBuilder.length() < length)
                lineBuilder.append(" ");
            return lineBuilder.toString();
        }
    }
}
