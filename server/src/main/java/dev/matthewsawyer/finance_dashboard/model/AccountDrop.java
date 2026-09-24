package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A finished stretch when Plaid stopped returning an account, from the day it was dropped until
 * the day it came back. An account's current drop, if any, is on the account itself.
 */
@Entity
@Table(name = "account_drops")
public class AccountDrop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "dropped_on", nullable = false, updatable = false)
    private LocalDate droppedOn;

    @Column(name = "restored_on", nullable = false, updatable = false)
    private LocalDate restoredOn;

    protected AccountDrop() {
    }

    public AccountDrop(String accountId, UUID userId, LocalDate droppedOn, LocalDate restoredOn) {
        this.accountId = accountId;
        this.userId = userId;
        this.droppedOn = droppedOn;
        this.restoredOn = restoredOn;
    }

    public String getAccountId() {
        return accountId;
    }

    public LocalDate getDroppedOn() {
        return droppedOn;
    }

    public LocalDate getRestoredOn() {
        return restoredOn;
    }

    /** Whether the account was out on {@code day}; the day it came back counts again. */
    public boolean covers(LocalDate day) {
        return !day.isBefore(droppedOn) && day.isBefore(restoredOn);
    }
}
