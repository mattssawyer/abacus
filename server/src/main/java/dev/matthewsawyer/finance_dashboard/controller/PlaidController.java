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
import dev.matthewsawyer.finance_dashboard.model.PlaidPublicToken;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidPublicTokenRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.service.PlaidTokenEncryption;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final PlaidPublicTokenRepository plaidPublicTokenRepository;
    private final UserService userService;
    private final PlaidTokenEncryption tokenEncryption;

    public PlaidController(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidPublicTokenRepository plaidPublicTokenRepository,
            UserService userService,
            PlaidTokenEncryption tokenEncryption
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.plaidPublicTokenRepository = plaidPublicTokenRepository;
        this.userService = userService;
        this.tokenEncryption = tokenEncryption;
    }

    @PostMapping("/create-link-token")
    public Map<String, String> createLinkToken(@AuthenticationPrincipal Jwt jwt) throws IOException {
        User user = userService.getOrCreateUser(jwt);
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(new LinkTokenCreateRequestUser().clientUserId(user.getId().toString()))
                .clientName("Abacus")
                .products(List.of(Products.TRANSACTIONS))
                .countryCodes(List.of(CountryCode.US))
                .language("en");

        Response<LinkTokenCreateResponse> response = plaidApi.linkTokenCreate(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid link token create failed");
        }

        return Map.of("link_token", response.body().getLinkToken());
    }

    @PostMapping("/public-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void savePublicToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SavePublicTokenRequest request
    ) {
        if (request.publicToken() == null || request.publicToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public token is required");
        }

        User user = userService.getOrCreateUser(jwt);
        plaidPublicTokenRepository.save(new PlaidPublicToken(user.getId(), request.publicToken()));
    }

    @PostMapping("/exchange-public-token")
    public Map<String, String> exchangePublicToken(@AuthenticationPrincipal Jwt jwt) throws IOException {
        User user = userService.getOrCreateUser(jwt);
        PlaidPublicToken storedToken = plaidPublicTokenRepository.findById(user.getId())
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No public token saved")
                );

        ItemPublicTokenExchangeRequest plaidRequest = new ItemPublicTokenExchangeRequest()
                .publicToken(storedToken.getPublicToken());

        Response<ItemPublicTokenExchangeResponse> response =
                plaidApi.itemPublicTokenExchange(plaidRequest).execute();

        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid token exchange failed");
        }

        ItemPublicTokenExchangeResponse exchange = response.body();
        String encryptedToken = tokenEncryption.encrypt(
                exchange.getAccessToken(), user.getId(), exchange.getItemId());
        plaidItemRepository.save(new PlaidItem(exchange.getItemId(), encryptedToken, user.getId()));
        plaidPublicTokenRepository.delete(storedToken);

        return Map.of("item_id", exchange.getItemId());
    }

    public record SavePublicTokenRequest(String publicToken) {
    }

    @GetMapping("/items")
    public Map<String, List<String>> getLinkedItems(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        List<String> itemIds = plaidItemRepository.findAllByUserIdOrderByItemIdAsc(user.getId())
                .stream()
                .map(PlaidItem::getItemId)
                .toList();

        return Map.of("item_ids", itemIds);
    }

    @GetMapping("/items/{itemId}/accounts")
    public AccountsGetResponse getAccounts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String itemId
    ) throws IOException {
        User user = userService.getOrCreateUser(jwt);
        PlaidItem plaidItem = plaidItemRepository.findByItemIdAndUserId(itemId, user.getId())
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Plaid item not found")
                );

        AccountsGetRequest request = new AccountsGetRequest()
                .accessToken(tokenEncryption.decrypt(
                        plaidItem.getEncryptedAccessToken(), user.getId(), plaidItem.getItemId()));

        Response<AccountsGetResponse> response = plaidApi.accountsGet(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid accounts get failed");
        }

        return response.body();
    }
}
