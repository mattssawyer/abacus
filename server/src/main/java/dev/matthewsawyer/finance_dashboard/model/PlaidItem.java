package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
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

    @Column(name = "transactions_cursor", columnDefinition = "TEXT")
    private String transactionsCursor;

    @Column(name = "recurring_synced_at")
    private Instant recurringSyncedAt;

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

    public String getTransactionsCursor() {
        return transactionsCursor;
    }

    public void updateAccessToken(String encryptedAccessToken) {
        this.encryptedAccessToken = encryptedAccessToken;
    }

    public Instant getRecurringSyncedAt() {
        return recurringSyncedAt;
    }

    public void updateTransactionsCursor(String transactionsCursor) {
        this.transactionsCursor = transactionsCursor;
    }

    public void markRecurringSynced(Instant recurringSyncedAt) {
        this.recurringSyncedAt = recurringSyncedAt;
    }
}
