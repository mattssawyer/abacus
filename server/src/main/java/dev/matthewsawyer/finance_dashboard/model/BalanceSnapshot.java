package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** An account's balance as it stood on one day. */
@Entity
@Table(name = "balance_snapshots")
@IdClass(BalanceSnapshot.Key.class)
public class BalanceSnapshot {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Id
    @Column(name = "snapshot_date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "current_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentBalance;

    protected BalanceSnapshot() {
    }

    public BalanceSnapshot(String accountId, LocalDate date, UUID userId) {
        this.accountId = accountId;
        this.date = date;
        this.userId = userId;
    }

    public void updateBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    public String getAccountId() {
        return accountId;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public record Key(String accountId, LocalDate date) implements Serializable {
    }
}
