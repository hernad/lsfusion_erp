package equ.api.cashregister;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Date;

public class DiscountCard implements Serializable {
    public String idDiscountCard;
    public String numberDiscountCard;
    public String nameDiscountCard;
    public BigDecimal percentDiscountCard;
    public BigDecimal initialSumDiscountCard;
    public Date dateFromDiscountCard;
    public Date dateToDiscountCard;
    public String typeDiscountCard;
    public String firstNameContact;
    public String lastNameContact;
    public String middleNameContact;
    public Date birthdayContact;
    
    public DiscountCard(String idDiscountCard, String numberDiscountCard, String nameDiscountCard, BigDecimal percentDiscountCard,
                        BigDecimal initialSumDiscountCard, Date dateFromDiscountCard, Date dateToDiscountCard,
                        String typeDiscountCard, String firstNameContact, String lastNameContact, String middleNameContact, Date birthdayContact) {
        this.idDiscountCard = idDiscountCard;
        this.numberDiscountCard = numberDiscountCard;
        this.nameDiscountCard = nameDiscountCard;
        this.percentDiscountCard = percentDiscountCard;
        this.initialSumDiscountCard = initialSumDiscountCard;
        this.dateFromDiscountCard = dateFromDiscountCard;
        this.dateToDiscountCard = dateToDiscountCard;
        this.typeDiscountCard = typeDiscountCard;
        this.firstNameContact = firstNameContact;
        this.lastNameContact = lastNameContact;
        this.middleNameContact = middleNameContact;
        this.birthdayContact = birthdayContact;
    }
    
}
