package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.service.PlaidTokenEncryption;
import dev.matthewsawyer.finance_dashboard.service.PlaidTransactionSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/plaid")
public class PlaidController {

    private static final Logger log = LoggerFactory.getLogger(PlaidController.class);

    private static final int MAX_TRANSACTION_LIMIT = 100;

    // Plaid files paychecks and account transfers under the same category field as spending.
    private static final Set<String> NON_SPENDING_CATEGORIES =
            Set.of("INCOME", "TRANSFER_IN", "TRANSFER_OUT");

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final UserService userService;
    private final PlaidTokenEncryption tokenEncryption;
    private final PlaidTransactionSyncService transactionSyncService;
    private final String webhookUrl;

    public PlaidController(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidTransactionRepository transactionRepository,
            UserService userService,
            PlaidTokenEncryption tokenEncryption,
            PlaidTransactionSyncService transactionSyncService,
            @Value("${plaid.webhook.url:}") String webhookUrl
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.transactionRepository = transactionRepository;
        this.userService = userService;
        this.tokenEncryption = tokenEncryption;
        this.transactionSyncService = transactionSyncService;
        this.webhookUrl = webhookUrl;
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

        // Items linked without a URL never receive webhooks, so local runs without a tunnel
        // simply fall back to syncing at link time.
        if (!webhookUrl.isBlank()) {
            request.webhook(webhookUrl);
        }

        Response<LinkTokenCreateResponse> response = plaidApi.linkTokenCreate(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid link token create failed");
        }

        return Map.of("link_token", response.body().getLinkToken());
    }

    @PostMapping("/items")
    public Map<String, String> exchangePublicToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ExchangePublicTokenRequest request
    ) throws IOException {
        if (request.publicToken() == null || request.publicToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public token is required");
        }

        User user = userService.getOrCreateUser(jwt);
        ItemPublicTokenExchangeRequest plaidRequest = new ItemPublicTokenExchangeRequest()
                .publicToken(request.publicToken());

        Response<ItemPublicTokenExchangeResponse> response =
                plaidApi.itemPublicTokenExchange(plaidRequest).execute();

        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid token exchange failed");
        }

        // Plaid returns the same item_id when an institution is re-linked, so this upserts
        // onto the existing row to keep its transactions cursor.
        ItemPublicTokenExchangeResponse exchange = response.body();
        String encryptedToken = tokenEncryption.encrypt(
                exchange.getAccessToken(), user.getId(), exchange.getItemId());
        PlaidItem item = plaidItemRepository.findByItemIdAndUserId(exchange.getItemId(), user.getId())
                .orElseGet(() -> new PlaidItem(exchange.getItemId(), encryptedToken, user.getId()));
        item.updateAccessToken(encryptedToken);
        plaidItemRepository.save(item);

        syncTransactions(item);

        return Map.of("item_id", exchange.getItemId());
    }

    /**
     * The item is already linked at this point, so a sync failure must not fail the
     * request; the transactions webhook backfills whatever this missed.
     */
    private void syncTransactions(PlaidItem item) {
        try {
            transactionSyncService.syncItem(item);
        } catch (IOException | RuntimeException e) {
            log.warn("Initial transactions sync failed for item {}", item.getItemId(), e);
        }
    }

    public record ExchangePublicTokenRequest(String publicToken) {
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

    @GetMapping("/transactions")
    public Map<String, List<TransactionResponse>> getTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "25") int limit
    ) {
        if (limit < 1 || limit > MAX_TRANSACTION_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_TRANSACTION_LIMIT);
        }

        User user = userService.getOrCreateUser(jwt);
        List<TransactionResponse> transactions = transactionRepository
                .findAllByUserIdOrderByTransactionDateDescTransactionIdAsc(
                        user.getId(), Pageable.ofSize(limit))
                .stream()
                .map(TransactionResponse::from)
                .toList();

        return Map.of("transactions", transactions);
    }

    public record TransactionResponse(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("date") LocalDate date,
            @JsonProperty("name") String name,
            @JsonProperty("merchant_name") String merchantName,
            @JsonProperty("logo_url") String logoUrl,
            @JsonProperty("pending") boolean pending,
            @JsonProperty("category") String category
    ) {
        static TransactionResponse from(PlaidTransaction transaction) {
            return new TransactionResponse(
                    transaction.getTransactionId(),
                    transaction.getAccountId(),
                    transaction.getAmount(),
                    transaction.getIsoCurrencyCode(),
                    transaction.getTransactionDate(),
                    transaction.getName(),
                    transaction.getMerchantName(),
                    transaction.getLogoUrl(),
                    transaction.isPending(),
                    transaction.getPersonalFinanceCategoryPrimary()
            );
        }
    }

    @GetMapping("/spending/by-category")
    public SpendingByCategoryResponse getSpendingByCategory(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        List<CategorySpend> categories = transactionRepository
                .sumSpendingByCategory(user.getId(), start, end, NON_SPENDING_CATEGORIES)
                .stream()
                // Refunds can net a category to zero or below, which a pie chart cannot show.
                .filter(total -> total.getTotal() != null && total.getTotal().signum() > 0)
                .map(total -> new CategorySpend(total.getCategory(), total.getTotal()))
                .sorted(Comparator.comparing(CategorySpend::amount).reversed())
                .toList();

        BigDecimal total = categories.stream()
                .map(CategorySpend::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SpendingByCategoryResponse(start, end, total, categories);
    }

    public record SpendingByCategoryResponse(
            @JsonProperty("start") LocalDate start,
            @JsonProperty("end") LocalDate end,
            @JsonProperty("total") BigDecimal total,
            @JsonProperty("categories") List<CategorySpend> categories
    ) {
    }

    public record CategorySpend(
            @JsonProperty("category") String category,
            @JsonProperty("amount") BigDecimal amount
    ) {
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
