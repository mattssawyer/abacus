package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class PlaidAccount {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "official_name", length = 512)
    private String officialName;

    @Column(length = 16)
    private String mask;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 64)
    private String subtype;

    @Column(name = "available_balance", precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "current_balance", precision = 19, scale = 4)
    private BigDecimal currentBalance;

    @Column(name = "limit_amount", precision = 19, scale = 4)
    private BigDecimal limitAmount;

    @Column(name = "iso_currency_code", length = 8)
    private String isoCurrencyCode;

    @Column(name = "unofficial_currency_code", length = 16)
    private String unofficialCurrencyCode;

    @Column(name = "dropped_on")
    private LocalDate droppedOn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaidAccount() {
    }

    public PlaidAccount(String accountId, String itemId, UUID userId) {
        this.accountId = accountId;
        this.itemId = itemId;
        this.userId = userId;
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    public void updateSnapshot(
            String name,
            String officialName,
            String mask,
            String type,
            String subtype,
            BigDecimal availableBalance,
            BigDecimal currentBalance,
            BigDecimal limitAmount,
            String isoCurrencyCode,
            String unofficialCurrencyCode
    ) {
        this.name = name;
        this.officialName = officialName;
        this.mask = mask;
        this.type = type;
        this.subtype = subtype;
        this.availableBalance = availableBalance;
        this.currentBalance = currentBalance;
        this.limitAmount = limitAmount;
        this.isoCurrencyCode = isoCurrencyCode;
        this.unofficialCurrencyCode = unofficialCurrencyCode;
    }

    /** Plaid stopped returning this account on {@code day}; an earlier drop date is kept. */
    public void drop(LocalDate day) {
        if (droppedOn == null) {
            droppedOn = day;
        }
    }

    /** Plaid is returning this account again. */
    public void restore() {
        droppedOn = null;
    }

    public LocalDate getDroppedOn() {
        return droppedOn;
    }

    public boolean isDropped() {
        return droppedOn != null;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getItemId() {
        return itemId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getOfficialName() {
        return officialName;
    }

    public String getMask() {
        return mask;
    }

    public String getType() {
        return type;
    }

    public String getSubtype() {
        return subtype;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    public String getIsoCurrencyCode() {
        return isoCurrencyCode;
    }

    public String getUnofficialCurrencyCode() {
        return unofficialCurrencyCode;
    }
}
