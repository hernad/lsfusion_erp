package lsfusion.erp.region.by.integration.topby;

import lsfusion.server.ServerLoggers;
import lsfusion.server.classes.ConcreteCustomClass;
import lsfusion.server.classes.CustomClass;
import lsfusion.server.classes.TimeClass;
import lsfusion.server.context.Context;
import lsfusion.server.context.ContextAwareDaemonThreadFactory;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.integration.*;
import lsfusion.server.lifecycle.LifecycleAdapter;
import lsfusion.server.lifecycle.LifecycleEvent;
import lsfusion.server.logics.*;
import lsfusion.server.logics.scripted.ScriptingBusinessLogics;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import java.io.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.apache.commons.lang.StringUtils.trimToEmpty;

public class TopByDaemon extends LifecycleAdapter implements InitializingBean {
    private static final Logger logger = ServerLoggers.systemLogger;

    public ExecutorService daemonTasksExecutor;

    private BusinessLogics businessLogics;
    private ScriptingLogicsModule topByLM;
    private DBManager dbManager;
    private Context instanceContext;
    private String directoryIn;
    private String directoryOut;
    private Integer sleep;

    public TopByDaemon(ScriptingBusinessLogics businessLogics, DBManager dbManager, LogicsInstance logicsInstance) {
        this.businessLogics = businessLogics;
        this.dbManager = dbManager;
        this.instanceContext = logicsInstance.getContext();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(businessLogics, "businessLogics must be specified");
        Assert.notNull(dbManager, "dbManager must be specified");
        Assert.notNull(instanceContext, "logicsInstance must be specified");
        Assert.notNull(directoryIn, "DirectoryIn must be specified");
        Assert.notNull(directoryOut, "DirectoryOut must be specified");
    }

    @Override
    protected void onStarted(LifecycleEvent event) {
        logger.info("Starting TopBy Daemon.");
        topByLM = businessLogics.getModule("TopBy");
        Assert.notNull(topByLM, "Module TopBy must be specified");
        try {
            setupDaemon(dbManager);
        } catch (SQLException | ScriptingErrorLog.SemanticErrorException e) {
            throw new RuntimeException("Error starting TopBy Daemon: ", e);
        }
    }

    public void setDirectoryOut(String directoryOut) {
        this.directoryOut = directoryOut;
    }

    public void setDirectoryIn(String directoryIn) {
        this.directoryIn = directoryIn;
    }

    public void setSleep(Integer sleep) {
        this.sleep = sleep;
    }

    public void setupDaemon(DBManager dbManager) throws SQLException, ScriptingErrorLog.SemanticErrorException {

        if (daemonTasksExecutor != null)
            daemonTasksExecutor.shutdown();

        daemonTasksExecutor = Executors.newSingleThreadExecutor(new ContextAwareDaemonThreadFactory(instanceContext, "topby-daemon"));
        daemonTasksExecutor.submit(new DaemonTask(dbManager));
    }

    class DaemonTask implements Runnable {
        DBManager dbManager;

        public DaemonTask(DBManager dbManager) {
            this.dbManager = dbManager;
        }

        public void run() {
            logger.info("TopByDaemon started");
            if (sleep == null)
                sleep = 10000;
            while (true) {
                try {
                    File[] files = new File(directoryIn).listFiles(getFileFilter("BLRWBL", "BLRDLN"));
                    File[] apnFiles = new File(directoryIn).listFiles(getFileFilter("BLRAPN"));
                    List<File> filesToDelete = new ArrayList<>();
                    if (files != null && files.length > 0) {
                        logger.info(String.format("TopByDaemon: %s file(s) found in %s", files.length, directoryIn));
                        for (File file : files) {
                            try {
                                //step0: получили файл BLRWBL/BLRDLN
                                String[] fileName = file.getName().split("_");
                                if (fileName.length == 3) {
                                    boolean wbl = fileName[0].equals("BLRWBL");

                                    boolean apnFound = false;
                                    for (File apnFile : apnFiles) {
                                        APNDocument apnDocument = readAPNDocument(apnFile);
                                        if (apnDocument != null && apnDocument.code.equals("2560")) {
                                            if (!apnFile.delete())
                                                filesToDelete.add(apnFile);
                                            apnFound = true;
                                        }
                                    }
                                    if (!apnFound) {
                                        logger.info("Warning: no apn file found in " + directoryIn);
                                    }

                                    final InputDocument inputDocument = readInputFile(file, wbl);

                                    Integer uniqueMessageNumber;
                                    try (DataSession session = dbManager.createSession()) {
                                        uniqueMessageNumber = readUniqueMessageNumber(session);
                                    }
                                    //step1: формируем ответ на BLRWBL/BLRDLN
                                    createAPNDocument(directoryOut, inputDocument, uniqueMessageNumber, wbl ? "BLRWBL" : "BLRDLN", "2650");

                                    //step2: ждём подтверждения приёма нашего ответа
                                    while (!proceededAPNDocument(directoryIn, inputDocument, "2551", filesToDelete)) {
                                        logger.info("waiting for creation of BLRAPN 2551 in " + directoryIn);
                                        Thread.sleep(sleep);
                                    }

                                    if(inputDocument.status != null && inputDocument.status.equals("9")) {
                                        //step3: формируем сообщение
                                        createOutputDocument(directoryOut, uniqueMessageNumber, inputDocument, wbl);

                                        //step4: ждём подтверждения приёма нашего сообщения
                                        while (!proceededAPNDocument(directoryIn, inputDocument, "2550", filesToDelete)) {
                                            logger.info("waiting for creation of BLRAPN 2550 in " + directoryIn);
                                            Thread.sleep(sleep);
                                        }
                                        while (!proceededAPNDocument(directoryIn, inputDocument, "2650", filesToDelete)) {
                                            logger.info("waiting for creation of BLRAPN 2650 in " + directoryIn);
                                            Thread.sleep(sleep);
                                        }
                                    }

                                    //step5: сохраняем накладную
                                    if(inputDocument.status != null) {
                                        switch (inputDocument.status) {
                                            case "9":
                                                logger.info(String.format("Import %s: started", file.getAbsolutePath()));
                                                importUserInvoice(inputDocument, uniqueMessageNumber);
                                                logger.info(String.format("Import %s: successfully finished", file.getAbsolutePath()));
                                                break;
                                            case "1":
                                                try (DataSession session = dbManager.createSession()) {
                                                    ObjectValue invoiceObject = topByLM.findProperty("Purchase.userInvoiceId").readClasses(session, new DataObject(inputDocument.seriesNumber));
                                                    if (invoiceObject instanceof DataObject) {
                                                        topByLM.findProperty("isCancelledUserInvoice").change(true, session, (DataObject) invoiceObject);
                                                        session.apply(businessLogics);
                                                    }
                                                }
                                                break;
                                            default:
                                                logger.error("Unknown status of document, file: " + file.getAbsolutePath());
                                                break;
                                        }
                                    }
                                } else {
                                    logger.info("Incorrect file found, will be deleted: " + file.getAbsolutePath());
                                }
                            } catch (Exception e) {
                                logger.error("TopByDaemon error", e);
                            } finally {
                                if (!file.delete())
                                    filesToDelete.add(file);
                            }
                            File[] aFiles = new File(directoryIn).listFiles(getFileFilter("A_"));
                            if (aFiles != null) {
                                for (File aFile : aFiles) {
                                    if (!aFile.delete())
                                        aFile.deleteOnExit();
                                }
                            }
                            for (File f : filesToDelete) {
                                if (!f.delete())
                                    f.deleteOnExit();
                            }
                        }
                    }
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    logger.error("TopByDaemon error", e);
                }
            }
        }

        private void importUserInvoice(InputDocument inputDocument, Integer uniqueMessageNumber) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
            List<ImportProperty<?>> props = new ArrayList<>();
            List<ImportField> fields = new ArrayList<>();
            List<ImportKey<?>> keys = new ArrayList<>();

            List<List<Object>> data = initData(inputDocument.detailList.size());

            try (DataSession session = dbManager.createSession()) {

                ImportField idUserInvoiceField = new ImportField(topByLM.findProperty("idUserInvoice"));
                ImportKey<?> userInvoiceKey = new ImportKey((ConcreteCustomClass) topByLM.findClass("Purchase.UserInvoice"),
                        topByLM.findProperty("userInvoiceId").getMapping(idUserInvoiceField));
                keys.add(userInvoiceKey);
                props.add(new ImportProperty(idUserInvoiceField, topByLM.findProperty("idUserInvoice").getMapping(userInvoiceKey)));
                fields.add(idUserInvoiceField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.seriesNumber);

                ImportField idOperationField = new ImportField(topByLM.findProperty("Purchase.idOperation"));
                ImportKey<?> operationKey = new ImportKey((CustomClass) topByLM.findClass("Purchase.Operation"),
                        topByLM.findProperty("Purchase.operationId").getMapping(idOperationField));
                keys.add(operationKey);
                props.add(new ImportProperty(idOperationField, topByLM.findProperty("Purchase.operationUserInvoice").getMapping(userInvoiceKey),
                        topByLM.object(topByLM.findClass("Purchase.Operation")).getMapping(operationKey)));
                fields.add(idOperationField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(null);

                ImportField GLNSupplierField = new ImportField(topByLM.findProperty("GLNLegalEntity"));
                ImportKey<?> supplierKey = new ImportKey((ConcreteCustomClass) topByLM.findClass("LegalEntity"),
                        topByLM.findProperty("legalEntityGLN").getMapping(GLNSupplierField));
                supplierKey.skipKey = true;
                keys.add(supplierKey);
                props.add(new ImportProperty(GLNSupplierField, topByLM.findProperty("GLNLegalEntity").getMapping(supplierKey), true));
                props.add(new ImportProperty(GLNSupplierField, topByLM.findProperty("Purchase.supplierUserInvoice").getMapping(userInvoiceKey),
                        topByLM.object(topByLM.findClass("LegalEntity")).getMapping(supplierKey)));
                fields.add(GLNSupplierField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.glnSupplier);

                ImportField nameSupplierField = new ImportField(topByLM.findProperty("nameLegalEntity"));
                props.add(new ImportProperty(nameSupplierField, topByLM.findProperty("nameLegalEntity").getMapping(supplierKey), true));
                fields.add(nameSupplierField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.nameSupplier);

                ImportField addressSupplierField = new ImportField(topByLM.findProperty("addressLegalEntity"));
                props.add(new ImportProperty(addressSupplierField, topByLM.findProperty("addressLegalEntity").getMapping(supplierKey), true));
                fields.add(addressSupplierField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.addressSupplier);

                ImportField UNPSupplierField = new ImportField(topByLM.findProperty("UNPLegalEntity"));
                props.add(new ImportProperty(UNPSupplierField, topByLM.findProperty("UNPLegalEntity").getMapping(supplierKey), true));
                fields.add(UNPSupplierField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.UNPSupplier);

                ImportField GLNSupplierStockField = new ImportField(topByLM.findProperty("GLNStock"));
                ImportKey<?> supplierStockKey = new ImportKey((CustomClass) topByLM.findClass("Stock"),
                        topByLM.findProperty("stockGLN").getMapping(GLNSupplierStockField));
                supplierStockKey.skipKey = true;
                keys.add(supplierStockKey);
                props.add(new ImportProperty(GLNSupplierStockField, topByLM.findProperty("GLNStock").getMapping(supplierStockKey), true));
                props.add(new ImportProperty(GLNSupplierStockField, topByLM.findProperty("Purchase.supplierStockUserInvoice").getMapping(userInvoiceKey),
                        topByLM.object(topByLM.findClass("Stock")).getMapping(supplierStockKey)));
                fields.add(GLNSupplierStockField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.glnSupplierStock);

                ImportField addressSupplierStockField = new ImportField(topByLM.findProperty("addressStock"));
                props.add(new ImportProperty(addressSupplierStockField, topByLM.findProperty("addressStock").getMapping(supplierStockKey), true));
                fields.add(addressSupplierStockField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.addressSupplierStock);

                ImportField GLNCustomerField = new ImportField(topByLM.findProperty("GLNLegalEntity"));
                ImportKey<?> customerKey = new ImportKey((CustomClass) topByLM.findClass("LegalEntity"),
                        topByLM.findProperty("legalEntityGLN").getMapping(GLNCustomerField));
                customerKey.skipKey = true;
                keys.add(customerKey);
                props.add(new ImportProperty(GLNCustomerField, topByLM.findProperty("GLNLegalEntity").getMapping(customerKey), true));
                props.add(new ImportProperty(GLNCustomerField, topByLM.findProperty("Purchase.customerUserInvoice").getMapping(userInvoiceKey),
                        topByLM.object(topByLM.findClass("LegalEntity")).getMapping(customerKey)));
                fields.add(GLNCustomerField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.glnCustomer);

                ImportField nameCustomerField = new ImportField(topByLM.findProperty("nameLegalEntity"));
                props.add(new ImportProperty(nameCustomerField, topByLM.findProperty("nameLegalEntity").getMapping(customerKey), true));
                fields.add(nameCustomerField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.nameCustomer);

                ImportField addressCustomerField = new ImportField(topByLM.findProperty("addressLegalEntity"));
                props.add(new ImportProperty(addressCustomerField, topByLM.findProperty("addressLegalEntity").getMapping(customerKey), true));
                fields.add(addressCustomerField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.addressCustomer);

                ImportField UNPCustomerField = new ImportField(topByLM.findProperty("UNPLegalEntity"));
                props.add(new ImportProperty(UNPCustomerField, topByLM.findProperty("UNPLegalEntity").getMapping(customerKey), true));
                fields.add(UNPCustomerField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.UNPCustomer);

                ImportField GLNCustomerStockField = new ImportField(topByLM.findProperty("GLNStock"));
                ImportKey<?> customerStockKey = new ImportKey((CustomClass) topByLM.findClass("Stock"),
                        topByLM.findProperty("stockGLN").getMapping(GLNCustomerStockField));
                customerStockKey.skipKey = true;
                keys.add(customerStockKey);
                props.add(new ImportProperty(GLNCustomerStockField, topByLM.findProperty("GLNStock").getMapping(customerStockKey), true));
                props.add(new ImportProperty(GLNCustomerStockField, topByLM.findProperty("Purchase.customerStockUserInvoice").getMapping(userInvoiceKey),
                        topByLM.object(topByLM.findClass("Stock")).getMapping(customerStockKey)));
                fields.add(GLNCustomerStockField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.glnCustomerStock);

                ImportField addressCustomerStockField = new ImportField(topByLM.findProperty("addressStock"));
                props.add(new ImportProperty(addressCustomerStockField, topByLM.findProperty("addressStock").getMapping(customerStockKey), true));
                fields.add(addressCustomerStockField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.addressCustomerStock);

                ImportField numberUserInvoiceField = new ImportField(topByLM.findProperty("Purchase.numberUserInvoice"));
                props.add(new ImportProperty(numberUserInvoiceField, topByLM.findProperty("Purchase.numberUserInvoice").getMapping(userInvoiceKey)));
                fields.add(numberUserInvoiceField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.uniqueNumber);

                ImportField seriesUserInvoiceField = new ImportField(topByLM.findProperty("Purchase.seriesUserInvoice"));
                props.add(new ImportProperty(seriesUserInvoiceField, topByLM.findProperty("Purchase.seriesUserInvoice").getMapping(userInvoiceKey)));
                fields.add(seriesUserInvoiceField);
                String series = inputDocument.seriesNumber == null ? null : inputDocument.seriesNumber.split("-")[0];
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(series);

                ImportField dateUserInvoiceField = new ImportField(topByLM.findProperty("Purchase.dateUserInvoice"));
                props.add(new ImportProperty(dateUserInvoiceField, topByLM.findProperty("Purchase.dateUserInvoice").getMapping(userInvoiceKey)));
                fields.add(dateUserInvoiceField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.date);

                ImportField timeUserInvoiceField = new ImportField(TimeClass.instance);
                props.add(new ImportProperty(timeUserInvoiceField, topByLM.findProperty("Purchase.timeUserInvoice").getMapping(userInvoiceKey)));
                fields.add(timeUserInvoiceField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.time);

                ImportField idUserInvoiceDetailField = new ImportField(topByLM.findProperty("idUserInvoiceDetail"));
                ImportKey<?> userInvoiceDetailKey = new ImportKey((ConcreteCustomClass) topByLM.findClass("Purchase.UserInvoiceDetail"),
                        topByLM.findProperty("userInvoiceDetailId").getMapping(idUserInvoiceDetailField));
                keys.add(userInvoiceDetailKey);
                props.add(new ImportProperty(idUserInvoiceDetailField, topByLM.findProperty("idUserInvoiceDetail").getMapping(userInvoiceDetailKey)));
                props.add(new ImportProperty(idUserInvoiceField, topByLM.findProperty("Purchase.userInvoiceUserInvoiceDetail").getMapping(userInvoiceDetailKey),
                        topByLM.object(topByLM.findClass("Purchase.UserInvoice")).getMapping(userInvoiceKey)));
                fields.add(idUserInvoiceDetailField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).id);

                ImportField idBarcodeField = new ImportField(topByLM.findProperty("idBarcode"));
                ImportKey<?> itemKey = new ImportKey((CustomClass) topByLM.findClass("Item"),
                        topByLM.findProperty("skuBarcodeId").getMapping(idBarcodeField));
                keys.add(itemKey);
                props.add(new ImportProperty(idBarcodeField, topByLM.findProperty("Purchase.skuInvoiceDetail").getMapping(userInvoiceDetailKey),
                        topByLM.object(topByLM.findClass("Item")).getMapping(itemKey)));
                fields.add(idBarcodeField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).barcode);

                ImportField quantityUserInvoiceDetailField = new ImportField(topByLM.findProperty("Purchase.quantityUserInvoiceDetail"));
                props.add(new ImportProperty(quantityUserInvoiceDetailField, topByLM.findProperty("Purchase.quantityUserInvoiceDetail").getMapping(userInvoiceDetailKey)));
                fields.add(quantityUserInvoiceDetailField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).quantity);

                ImportField priceUserInvoiceDetail = new ImportField(topByLM.findProperty("Purchase.priceUserInvoiceDetail"));
                props.add(new ImportProperty(priceUserInvoiceDetail, topByLM.findProperty("Purchase.priceUserInvoiceDetail").getMapping(userInvoiceDetailKey)));
                fields.add(priceUserInvoiceDetail);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).price);

                ImportField sumNetWeightUserInvoiceDetail = new ImportField(topByLM.findProperty("Purchase.sumNetWeightUserInvoiceDetail"));
                props.add(new ImportProperty(sumNetWeightUserInvoiceDetail, topByLM.findProperty("Purchase.sumNetWeightUserInvoiceDetail").getMapping(userInvoiceDetailKey)));
                props.add(new ImportProperty(sumNetWeightUserInvoiceDetail, topByLM.findProperty("Purchase.sumGrossWeightUserInvoiceDetail").getMapping(userInvoiceDetailKey)));
                fields.add(sumNetWeightUserInvoiceDetail);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).netWeight);

                ImportField valueVATUserInvoiceDetailField = new ImportField(topByLM.findProperty("Purchase.valueVATUserInvoiceDetail"));
                ImportKey<?> VATKey = new ImportKey((ConcreteCustomClass) topByLM.findClass("Range"),
                        topByLM.findProperty("valueCurrentVATDefaultValue").getMapping(valueVATUserInvoiceDetailField));
                VATKey.skipKey = true;
                keys.add(VATKey);
                props.add(new ImportProperty(valueVATUserInvoiceDetailField, topByLM.findProperty("Purchase.VATUserInvoiceDetail").getMapping(userInvoiceDetailKey),
                        topByLM.object(topByLM.findClass("Range")).getMapping(VATKey)));
                fields.add(valueVATUserInvoiceDetailField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).vat);

                ImportField VATSumUserInvoiceDetailField = new ImportField(topByLM.findProperty("Purchase.VATSumUserInvoiceDetail"));
                props.add(new ImportProperty(VATSumUserInvoiceDetailField, topByLM.findProperty("Purchase.VATSumUserInvoiceDetail").getMapping(userInvoiceDetailKey)));
                fields.add(VATSumUserInvoiceDetailField);
                for (int i = 0; i < inputDocument.detailList.size(); i++)
                    data.get(i).add(inputDocument.detailList.get(i).vatSum);

                ImportTable table = new ImportTable(fields, data);

                topByLM.findProperty("maxMessageNumber").change(uniqueMessageNumber, session);

                session.pushVolatileStats("TB_UI");
                IntegrationService service = new IntegrationService(session, table, keys, props);
                service.synchronize(true, false);
                session.apply(businessLogics);
                session.popVolatileStats();
            }
        }

        private Integer readUniqueMessageNumber(DataSession session) {
            Integer uniqueMessageNumber;
            try {
                uniqueMessageNumber = (Integer) topByLM.findProperty("maxMessageNumber").read(session);
                if (uniqueMessageNumber == null)
                    uniqueMessageNumber = 0;
            } catch (SQLException | ScriptingErrorLog.SemanticErrorException | SQLHandledException e) {
                logger.error("TopBy session error: ", e);
                return 0;
            }
            return uniqueMessageNumber;
        }

        private InputDocument readInputFile(File file, boolean wbl) throws IOException, ParseException {

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                List<InputDocumentDetail> detailList = new ArrayList<>();
                String uniqueNumber = null;
                String status = null;
                String seriesNumber = null;
                String dateTime = null;
                String creationDateTime = null;
                String paperDate = null;
                String glnSupplier = null;
                String nameSupplier = null;
                String addressSupplier = null;
                String unnSupplier = null;
                String glnCustomer = null;
                String nameCustomer = null;
                String addressCustomer = null;
                String unnCustomer = null;
                String glnSupplierStock = null;
                String addressSupplierStock = null;
                String contactSupplierStock = null;
                String glnCustomerStock = null;
                String addressCustomerStock = null;
                String contactCustomerStock = null;

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] params = line.split("\\|");
                    if (params[0].equals("0")) {
                        uniqueNumber = params.length > 1 ? params[1] : null;
                        dateTime = params.length > 2 ? params[2] : null;
                        glnSupplier = params.length > 4 ? params[4] : null;
                        glnCustomer = params.length > 5 ? params[5] : null;
                        creationDateTime = params.length > 9 ? params[9] : null;
                        status = params.length > 10 ? params[10] : null;
                        seriesNumber = params.length > 11 ? params[11] : null;
                        paperDate = params.length > 12 ? params[12] : null;
                        nameSupplier = wbl ? (params.length > 17 ? params[17] : null) : (params.length > 16 ? params[16] : null);
                        addressSupplier = wbl ? (params.length > 18 ? params[18] : null) : params.length > 17 ? params[17] : null;
                        unnSupplier = wbl ? (params.length > 19 ? params[19] : null) : (params.length > 18 ? params[18] : null);
                        nameCustomer = wbl ? (params.length > 22 ? params[22] : null) : (params.length > 21 ? params[21] : null);
                        addressCustomer = wbl ? (params.length > 23 ? params[23] : null) : (params.length > 22 ? params[22] : null);
                        unnCustomer = wbl ? (params.length > 24 ? params[24] : null) : (params.length > 23 ? params[23] : null);
                        glnSupplierStock = wbl ? (params.length > 32 ? params[32] : null) : null;
                        addressSupplierStock = wbl ? (params.length > 33 ? params[33] : null) : null;
                        contactSupplierStock = wbl ? (params.length > 34 ? params[34] : null) : null;
                        glnCustomerStock = wbl ? (params.length > 29 ? params[29] : null) : null;
                        addressCustomerStock = wbl ? (params.length > 30 ? params[30] : null) : null;
                        contactCustomerStock = wbl ? (params.length > 31 ? params[31] : null) : null;
                    } else if (params[0].equals("3")) {
                        String barcode = params.length > 2 ? params[2] : null;
                        BigDecimal netWeight = wbl ? (params.length > 6 ? new BigDecimal(params[6]) : null) : null;
                        BigDecimal quantity = wbl ? (params.length > 7 ? new BigDecimal(params[7]) : null) : (params.length > 6 ? new BigDecimal(params[6]) : null);
                        BigDecimal vat = wbl ? (params.length > 12 ? new BigDecimal(params[12]) : null) : (params.length > 10 ? new BigDecimal(params[10]) : null);
                        BigDecimal vatSum = wbl ? (params.length > 14 ? new BigDecimal(params[14]) : null) : (params.length > 12 ? new BigDecimal(params[12]) : null);
                        BigDecimal price = wbl ? (params.length > 16 ? new BigDecimal(params[16]) : null) : (params.length > 16 ? new BigDecimal(params[16]) : null);
                        String id = uniqueNumber + "/" + barcode;
                        detailList.add(new InputDocumentDetail(id, barcode, quantity, price, vat, vatSum, netWeight));
                    }
                }
                Long timestamp = dateTime == null ? null : new SimpleDateFormat("yyyyMMddHHmmss").parse(dateTime).getTime();
                Date date = timestamp == null ? null : new Date(timestamp);
                Time time = timestamp == null ? null : new Time(timestamp);
                return new InputDocument(detailList, status, "123456789", uniqueNumber, seriesNumber, date, time, dateTime, creationDateTime,
                        paperDate, glnSupplier, nameSupplier, addressSupplier, unnSupplier, glnCustomer, nameCustomer,
                        addressCustomer, unnCustomer, glnSupplierStock, addressSupplierStock, contactSupplierStock,
                        glnCustomerStock, addressCustomerStock, contactCustomerStock);
            }

        }

        private boolean proceededAPNDocument(String directoryIn, InputDocument inputDocument, String code, List<File> filesToDelete) throws IOException {
            File[] files = new File(directoryIn).listFiles(getFileFilter("BLRAPN_"));
            for (File file : files) {
                APNDocument apnDocument = readAPNDocument(file);
                if (apnDocument != null && apnDocument.seriesNumber != null && apnDocument.seriesNumber.equals(inputDocument.seriesNumber)
                        && apnDocument.code != null && apnDocument.code.equals(code)) {
                    if (!file.delete())
                        filesToDelete.add(file);
                    return true;
                }
            }
            return false;
        }

        private APNDocument readAPNDocument(File file) throws IOException {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] params = line.split("\\|");
                    if (params[0].equals("0")) {
                        String seriesNumber = params.length > 10 ? params[10] : null;
                        String code = params.length > 17 ? params[17] : null;
                        return new APNDocument(seriesNumber, code);
                    }
                }
                return null;
            }
        }

        private void createAPNDocument(String directoryOut, InputDocument inputDocument, Integer uniqueMessageNumber, String parentDocumentType, String apnCode) throws IOException {
            java.util.Date dateTime = Calendar.getInstance().getTime();
            File apnFile = new File(String.format("%s/BLRAPN_%s_%s.txt", directoryOut, inputDocument.glnSupplier, dateTime.getTime()));
            try (FileWriter writer = new FileWriter(apnFile)) {
                String dateTimeString = new SimpleDateFormat("yyyyMMddHHmmss").format(dateTime);
                uniqueMessageNumber++;
                String data = String.format("0|%s|%s|BLRAPN|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|",
                        uniqueMessageNumber, dateTimeString, inputDocument.glnCustomer, inputDocument.glnSupplier, inputDocument.userId, uniqueMessageNumber, 6,
                        dateTimeString, inputDocument.seriesNumber, inputDocument.paperDate, parentDocumentType, inputDocument.uniqueNumber,
                        inputDocument.creationDateTime, inputDocument.glnSupplier, inputDocument.glnCustomer, apnCode);
                writer.write(data);
            }
        }

        private void createOutputDocument(String directoryOut, Integer uniqueMessageNumber, InputDocument inputDocument, boolean wbl) throws IOException, SQLException, ScriptingErrorLog.SemanticErrorException, SQLHandledException {
            String nameChief;
            try (DataSession session = dbManager.createSession()) {
                nameChief = (String) topByLM.findProperty("nameCustomUserChiefLegalEntity").read(session, topByLM.findProperty("legalEntityGLN").readClasses(session, new DataObject(inputDocument.glnCustomer)));
            }
            java.util.Date dateTime = Calendar.getInstance().getTime();
            File outputFile = new File(String.format("%s/%s_%s_%s.txt", directoryOut, wbl ? "BLRWBR" : "BLRDNR", inputDocument.glnCustomer, dateTime.getTime()));
            try (FileWriter writer = new FileWriter(outputFile)) {
                String dateTimeString = new SimpleDateFormat("yyyyMMddHHmmss").format(dateTime);
                uniqueMessageNumber++;
                if (wbl)
                    writer.write(String.format("0|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|",
                            uniqueMessageNumber, dateTimeString, "BLRWBR", inputDocument.glnCustomer, inputDocument.glnSupplier, //5
                            inputDocument.userId, "700", uniqueMessageNumber, dateTimeString, "11", inputDocument.uniqueNumber, inputDocument.creationDateTime, //12
                            inputDocument.seriesNumber, inputDocument.paperDate, inputDocument.glnSupplier, inputDocument.nameSupplier, inputDocument.addressSupplier, //17
                            inputDocument.UNPSupplier, inputDocument.glnCustomer, inputDocument.nameCustomer, inputDocument.addressCustomer, //21
                            inputDocument.UNPCustomer, inputDocument.glnSupplierStock, inputDocument.addressSupplierStock, inputDocument.contactSupplierStock)); //25;
                else
                    writer.write(String.format("0|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|",
                            uniqueMessageNumber, dateTimeString, "BLRDNR", inputDocument.glnCustomer, inputDocument.glnSupplier, //5
                            inputDocument.userId, "270", uniqueMessageNumber, dateTimeString, "11", inputDocument.uniqueNumber, inputDocument.creationDateTime, //12
                            inputDocument.seriesNumber, inputDocument.paperDate, inputDocument.glnSupplier, inputDocument.nameSupplier, inputDocument.addressSupplier, //17
                            inputDocument.UNPSupplier, inputDocument.glnCustomer, inputDocument.nameCustomer, inputDocument.addressCustomer, //21
                            inputDocument.UNPCustomer, trimToEmpty(nameChief))); //23;
            }
        }

        private FilenameFilter getFileFilter(final String input) {
            return getFileFilter(input, null);
        }

        private FilenameFilter getFileFilter(final String input1, final String input2) {
            return new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(input1) || (input2 != null && name.startsWith(input2));
                }
            };
        }

        protected List<List<Object>> initData(int size) {
            List<List<Object>> data = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                data.add(new ArrayList<>());
            }
            return data;
        }
    }
}
