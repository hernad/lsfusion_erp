package equ.clt.handler.eqs;

import java.io.Serializable;

public class EQSSettings implements Serializable{

    private Boolean appendBarcode;
    private String giftCardPrefix;
    private Boolean skipIdDepartmentStore;

    public EQSSettings() {
    }

    public Boolean getAppendBarcode() {
        return appendBarcode;
    }

    public void setAppendBarcode(Boolean appendBarcode) {
        this.appendBarcode = appendBarcode;
    }

    public String getGiftCardPrefix() {
        return giftCardPrefix;
    }

    public void setGiftCardPrefix(String giftCardPrefix) {
        this.giftCardPrefix = giftCardPrefix;
    }

    public Boolean getSkipIdDepartmentStore() {
        return skipIdDepartmentStore;
    }

    public void setSkipIdDepartmentStore(Boolean skipIdDepartmentStore) {
        this.skipIdDepartmentStore = skipIdDepartmentStore;
    }
}