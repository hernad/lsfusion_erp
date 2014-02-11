package lsfusion.erp.integration.universal;


import lsfusion.server.logics.DataObject;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.Map;

public class UserPriceListDetail {
    public Boolean isPosted;
    public String idUserPriceListDetail;
    public String idUserPriceList;
    public String idItem;
    public String idItemGroup;
    public String barcodeItem;
    public String extraBarcodeItem;
    public String barcodePack;
    public BigDecimal amountPack;
    public String articleItem;
    public String captionItem;
    public String idUOMItem;
    public Date date;
    public Map<DataObject, BigDecimal> prices;
    public BigDecimal quantityAdjustment;


    public UserPriceListDetail(Boolean isPosted, String idUserPriceListDetail, String idUserPriceList, String idItem, 
                               String idItemGroup, String barcodeItem, String extraBarcodeItem, String barcodePack, 
                               BigDecimal amountPack, String articleItem, String captionItem, String idUOMItem, 
                               Date date, Map<DataObject, BigDecimal> prices, BigDecimal quantityAdjustment) {
        this.isPosted = isPosted;
        this.idUserPriceListDetail = idUserPriceListDetail;
        this.idUserPriceList = idUserPriceList;
        this.idItem = idItem;
        this.idItemGroup = idItemGroup;
        this.barcodeItem = barcodeItem;
        this.extraBarcodeItem = extraBarcodeItem;
        this.barcodePack = barcodePack;
        this.amountPack = amountPack;
        this.articleItem = articleItem;
        this.captionItem = captionItem;
        this.idUOMItem = idUOMItem;
        this.date = date;
        this.prices = prices;
        this.quantityAdjustment = quantityAdjustment;
    }
}
