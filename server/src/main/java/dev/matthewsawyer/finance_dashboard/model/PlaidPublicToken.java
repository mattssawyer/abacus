package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plaid_public_tokens")
public class PlaidPublicToken {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "public_token", nullable = false, length = 512)
    private String publicToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlaidPublicToken() {
    }

    public PlaidPublicToken(UUID userId, String publicToken) {
        this.userId = userId;
        this.publicToken = publicToken;
        this.createdAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        createdAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
