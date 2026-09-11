package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "plaid_items")
public class PlaidItem {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "access_token", nullable = false, length = 512)
    private String accessToken;

    protected PlaidItem() {
    }

    public PlaidItem(String itemId, String accessToken) {
        this.itemId = itemId;
        this.accessToken = accessToken;
    }

    public String getItemId() {
        return itemId;
    }

    public String getAccessToken() {
        return accessToken;
    }
}
