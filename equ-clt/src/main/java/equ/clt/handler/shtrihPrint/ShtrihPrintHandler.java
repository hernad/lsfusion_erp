package equ.clt.handler.shtrihPrint;

import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.Dispatch;
import com.jacob.com.LibraryLoader;
import com.jacob.com.Variant;
import equ.api.*;
import equ.api.scales.*;
import org.springframework.context.support.FileSystemXmlApplicationContext;

import java.io.IOException;
import java.util.List;

public class ShtrihPrintHandler extends ScalesHandler {

    private FileSystemXmlApplicationContext springContext;
    
    public ShtrihPrintHandler(FileSystemXmlApplicationContext springContext) {
        this.springContext = springContext;
    }

    @Override
    public void sendTransaction(TransactionScalesInfo transactionInfo, List<ScalesInfo> machineryInfoList) throws IOException {

        //System.setProperty(LibraryLoader.JACOB_DLL_PATH, "D:\\Projects\\platform\\equ-clt\\conf\\Shtrih\\jacob-1.15-M3.dll");

        ActiveXComponent shtrihActiveXComponent = new ActiveXComponent("AddIn.DrvLP");
        Dispatch shtrihDispatch = shtrihActiveXComponent.getObject();

        ScalesSettings shtrihSettings = (ScalesSettings) springContext.getBean("shtrihSettings");

        Variant pass = new Variant(30);

        Variant result = Dispatch.call(shtrihDispatch, "Connect");
        if (result.toString().equals("0")) {
            for (ScalesItemInfo item : transactionInfo.itemsList) {
                Integer barcode = Integer.parseInt(item.idBarcode.substring(0, 5));
                Integer pluNumber = item.pluNumber != null ? item.pluNumber : barcode;
                int deltaDaysExpiry = item.expirationDate == null ? 0 : (int) ((item.expirationDate.getTime() - System.currentTimeMillis()) / 1000 / 3600 / 24);
                Integer shelfLife = item.daysExpiry == null ? (deltaDaysExpiry >= 0 ? deltaDaysExpiry : 0) : item.daysExpiry.intValue();

                int len = item.name.length();
                String firstName = item.name.substring(0, len < 28 ? len : 28);
                String secondName = len < 28 ? "" : item.name.substring(28, len < 56 ? len : 56);

                shtrihActiveXComponent.setProperty("Password", pass);
                shtrihActiveXComponent.setProperty("PLUNumber", new Variant(pluNumber));
                shtrihActiveXComponent.setProperty("Price", new Variant(item.price));
                shtrihActiveXComponent.setProperty("Tare", new Variant(0));
                shtrihActiveXComponent.setProperty("ItemCode", new Variant(barcode));
                shtrihActiveXComponent.setProperty("NameFirst", new Variant(firstName));
                shtrihActiveXComponent.setProperty("NameSecond", new Variant(secondName));
                shtrihActiveXComponent.setProperty("ShelfLife", new Variant(shelfLife)); //срок хранения в днях
                String groupCode = item.hierarchyItemGroup.get(0) == null ? null : item.hierarchyItemGroup.get(0).replace("_", "");
                shtrihActiveXComponent.setProperty("GroupCode", new Variant(groupCode));
                shtrihActiveXComponent.setProperty("PictureNumber", new Variant(0));
                shtrihActiveXComponent.setProperty("ROSTEST", new Variant(0));
                shtrihActiveXComponent.setProperty("ExpiryDate", new Variant(item.expirationDate));
                shtrihActiveXComponent.setProperty("GoodsType", new Variant(item.splitItem ? 1 : 0));

                int start = 0;
                int i = 0;
                while(i < 9 && start < item.description.length()) {
                    shtrihActiveXComponent.setProperty("MessageNumber", new Variant(shtrihSettings.usePLUNumberInMessage ? item.pluNumber : item.descriptionNumber));
                    shtrihActiveXComponent.setProperty("StringNumber", new Variant(i+1));
                    String message = item.description.substring(start, Math.min(start + 50, item.description.length())).split("\n")[0];
                    shtrihActiveXComponent.setProperty("MessageString", new Variant(message));
                    start += message.length() + 1;
                    i++;
                    
                    result = Dispatch.call(shtrihDispatch, "SetMessageData");
                    if (!result.toString().equals("0")) {
                        throw new RuntimeException("ShtrihPrintHandler. Item # " + item.idBarcode + " Error # " + result.toString());
                    }                    
                }                

                result = Dispatch.call(shtrihDispatch, "SetPLUDataEx");
                if (!result.toString().equals("0")) {
                    throw new RuntimeException("ShtrihPrintHandler. Item # " + item.idBarcode + " Error # " + result.toString());
                }
            }
            result = Dispatch.call(shtrihDispatch, "Disconnect");
            if (!result.toString().equals("0")) {
                throw new RuntimeException("ShtrihPrintHandler. Disconnection error (# " + result.toString() + ")");
            }
        } else {
            Dispatch.call(shtrihDispatch, "Disconnect");
            throw new RuntimeException("ShtrihPrintHandler. Connection error (# " + result.toString() + ")");
        }
    }

    @Override
    public void sendSoftCheck(SoftCheckInfo softCheckInfo) throws IOException {
        
    }
}
