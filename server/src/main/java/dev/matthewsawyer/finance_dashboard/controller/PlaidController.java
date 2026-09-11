package dev.matthewsawyer.finance_dashboard.controller;

import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.CountryCode;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/plaid")
public class PlaidController {

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;

    public PlaidController(PlaidApi plaidApi, PlaidItemRepository plaidItemRepository) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
    }

    @PostMapping("/create-link-token")
    public Map<String, String> createLinkToken() throws IOException {
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(new LinkTokenCreateRequestUser().clientUserId("user-1"))
                .clientName("Finance Dashboard")
                .products(List.of(Products.TRANSACTIONS))
                .countryCodes(List.of(CountryCode.US))
                .language("en");

        Response<LinkTokenCreateResponse> response = plaidApi.linkTokenCreate(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid link token create failed");
        }

        return Map.of("link_token", response.body().getLinkToken());
    }

    @PostMapping("/exchange-public-token")
    public Map<String, String> exchangePublicToken(
            @RequestBody ExchangePublicTokenRequest request
    ) throws IOException {
        if (request.publicToken() == null || request.publicToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public token is required");
        }

        ItemPublicTokenExchangeRequest plaidRequest = new ItemPublicTokenExchangeRequest()
                .publicToken(request.publicToken());

        Response<ItemPublicTokenExchangeResponse> response =
                plaidApi.itemPublicTokenExchange(plaidRequest).execute();

        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid token exchange failed");
        }

        ItemPublicTokenExchangeResponse exchange = response.body();
        plaidItemRepository.save(new PlaidItem(exchange.getItemId(), exchange.getAccessToken()));

        return Map.of("item_id", exchange.getItemId());
    }

    public record ExchangePublicTokenRequest(String publicToken) {
    }

    @GetMapping("/items/{itemId}/accounts")
    public AccountsGetResponse getAccounts(@PathVariable String itemId) throws IOException {
        PlaidItem plaidItem = plaidItemRepository.findById(itemId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Plaid item not found")
                );

        AccountsGetRequest request = new AccountsGetRequest()
                .accessToken(plaidItem.getAccessToken());

        Response<AccountsGetResponse> response = plaidApi.accountsGet(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid accounts get failed");
        }

        System.out.println(response.body().getAccounts());
        return response.body();
    }
}