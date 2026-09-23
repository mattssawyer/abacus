package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.CountryCode;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Connects a user's Plaid items and stores their access for later syncs. */
@Service
public class PlaidItemLinking {

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final PlaidItemSync itemSync;
    private final String webhookUrl;

    public PlaidItemLinking(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidTokenEncryption tokenEncryption,
            PlaidItemSync itemSync,
            @Value("${plaid.webhook.url:}") String webhookUrl
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.tokenEncryption = tokenEncryption;
        this.itemSync = itemSync;
        this.webhookUrl = webhookUrl;
    }

    /** A short-lived token that opens Plaid Link in the browser for this user. */
    public String createLinkToken(UUID userId) {
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(new LinkTokenCreateRequestUser().clientUserId(userId.toString()))
                .clientName("Abacus")
                .products(List.of(Products.TRANSACTIONS))
                .countryCodes(List.of(CountryCode.US))
                .language("en");

        // Items linked without a URL never receive webhooks, so local runs without a tunnel
        // only sync at link time.
        if (!webhookUrl.isBlank()) {
            request.webhook(webhookUrl);
        }

        return PlaidCalls.execute(plaidApi.linkTokenCreate(request), "link token create")
                .getLinkToken();
    }

    /**
     * Exchanges the public token Plaid Link returned, stores the item's encrypted access token and
     * syncs it. Returns the item id. A failed sync does not fail the link.
     */
    public String link(UUID userId, String publicToken) {
        ItemPublicTokenExchangeResponse exchange = PlaidCalls.execute(
                plaidApi.itemPublicTokenExchange(
                        new ItemPublicTokenExchangeRequest().publicToken(publicToken)),
                "token exchange");

        // Plaid returns the same item_id when an institution is re-linked, so this upserts
        // onto the existing row to keep its transactions cursor.
        String encryptedToken = tokenEncryption.encrypt(
                exchange.getAccessToken(), userId, exchange.getItemId());
        PlaidItem item = plaidItemRepository.findByItemIdAndUserId(exchange.getItemId(), userId)
                .orElseGet(() -> new PlaidItem(exchange.getItemId(), encryptedToken, userId));
        item.updateAccessToken(encryptedToken);
        plaidItemRepository.save(item);

        itemSync.linked(item);
        return exchange.getItemId();
    }
}
