package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "plaid_items")
public class PlaidItem {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    protected PlaidItem() {
    }

    public PlaidItem(String itemId, String encryptedAccessToken, UUID userId) {
        this.itemId = itemId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.userId = userId;
    }

    public String getItemId() {
        return itemId;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public UUID getUserId() {
        return userId;
    }
}
