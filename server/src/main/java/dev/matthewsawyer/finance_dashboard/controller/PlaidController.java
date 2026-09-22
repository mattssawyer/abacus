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
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.service.PlaidRecurringStreamSyncService;
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
import java.util.UUID;

@RestController
@RequestMapping("/plaid")
public class PlaidController {

    private static final Logger log = LoggerFactory.getLogger(PlaidController.class);

    private static final int MAX_TRANSACTION_LIMIT = 100;
    private static final int DEFAULT_RECURRING_STREAMS = 8;
    private static final int MAX_RECURRING_STREAMS = 50;

    // Plaid files paychecks and account transfers under the same category field as spending.
    private static final Set<String> NON_SPENDING_CATEGORIES =
            Set.of("INCOME", "TRANSFER_IN", "TRANSFER_OUT");

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidAccountRepository accountRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final PlaidRecurringStreamRepository recurringStreamRepository;
    private final UserService userService;
    private final PlaidTokenEncryption tokenEncryption;
    private final PlaidTransactionSyncService transactionSyncService;
    private final PlaidRecurringStreamSyncService recurringStreamSyncService;
    private final String webhookUrl;

    public PlaidController(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidAccountRepository accountRepository,
            PlaidTransactionRepository transactionRepository,
            PlaidRecurringStreamRepository recurringStreamRepository,
            UserService userService,
            PlaidTokenEncryption tokenEncryption,
            PlaidTransactionSyncService transactionSyncService,
            PlaidRecurringStreamSyncService recurringStreamSyncService,
            @Value("${plaid.webhook.url:}") String webhookUrl
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.recurringStreamRepository = recurringStreamRepository;
        this.userService = userService;
        this.tokenEncryption = tokenEncryption;
        this.transactionSyncService = transactionSyncService;
        this.recurringStreamSyncService = recurringStreamSyncService;
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

        syncLinkedItem(item);

        return Map.of("item_id", exchange.getItemId());
    }

    /**
     * The item is already linked at this point, so a sync failure must not fail the
     * request; webhooks backfill whatever this missed.
     */
    private void syncLinkedItem(PlaidItem item) {
        try {
            transactionSyncService.syncItem(item);
        } catch (IOException | RuntimeException e) {
            log.warn("Initial transactions sync failed for item {}", item.getItemId(), e);
        }
        try {
            recurringStreamSyncService.syncItem(item);
        } catch (IOException | RuntimeException e) {
            log.warn("Initial recurring stream sync failed for item {}", item.getItemId(), e);
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
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(name = "account_id", required = false) String accountId
    ) {
        if (limit < 1 || limit > MAX_TRANSACTION_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_TRANSACTION_LIMIT);
        }

        User user = userService.getOrCreateUser(jwt);
        List<TransactionResponse> transactions = transactionRepository
                .findRecent(user.getId(), blankToNull(accountId), Pageable.ofSize(limit))
                .stream()
                .map(TransactionResponse::from)
                .toList();

        return Map.of("transactions", transactions);
    }

    @GetMapping("/transactions/recurring")
    public Map<String, List<RecurringStreamResponse>> getRecurringTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "account_id", required = false) String accountId,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        User user = userService.getOrCreateUser(jwt);
        backfillRecurringStreams(user.getId());

        String accountFilter = blankToNull(accountId);
        List<PlaidRecurringStream> stored = accountFilter == null
                ? recurringStreamRepository.findAllByUserId(user.getId())
                : recurringStreamRepository.findAllByUserIdAndAccountId(user.getId(), accountFilter);

        List<RecurringStreamResponse> streams = stored.stream()
                .map(RecurringStreamResponse::from)
                .sorted(Comparator
                        .comparing(RecurringStreamResponse::nextDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RecurringStreamResponse::lastDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int cap = resolveRecurringLimit(limit);
        if (streams.size() > cap) {
            streams = List.copyOf(streams.subList(0, cap));
        }

        return Map.of("streams", streams);
    }

    /**
     * Items linked before streams were stored have never been synced. Fetch once, then
     * later loads read Postgres.
     */
    private void backfillRecurringStreams(UUID userId) {
        for (PlaidItem item : plaidItemRepository.findAllByUserIdOrderByItemIdAsc(userId)) {
            if (item.getRecurringSyncedAt() != null) {
                continue;
            }
            try {
                recurringStreamSyncService.syncItem(item);
            } catch (IOException | RuntimeException e) {
                log.warn("Recurring stream backfill failed for item {}", item.getItemId(), e);
            }
        }
    }

    public record RecurringStreamResponse(
            @JsonProperty("stream_id") String streamId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("merchant_name") String merchantName,
            @JsonProperty("description") String description,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("next_date") LocalDate nextDate,
            @JsonProperty("last_date") LocalDate lastDate,
            @JsonProperty("is_inflow") boolean isInflow,
            @JsonProperty("category") String category,
            @JsonProperty("category_detailed") String categoryDetailed
    ) {
        static RecurringStreamResponse from(PlaidRecurringStream stream) {
            return new RecurringStreamResponse(
                    stream.getStreamId(),
                    stream.getAccountId(),
                    stream.getMerchantName(),
                    stream.getDescription(),
                    stream.getAmount(),
                    stream.getIsoCurrencyCode(),
                    stream.getFrequency(),
                    stream.getNextDate(),
                    stream.getLastDate(),
                    stream.isInflow(),
                    stream.getCategory(),
                    stream.getCategoryDetailed()
            );
        }
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
    public SpendingByCategoryResponse getSpendingByCategory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "account_id", required = false) String accountId
    ) {
        User user = userService.getOrCreateUser(jwt);
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        List<CategorySpend> categories = transactionRepository
                .sumSpendingByCategory(
                        user.getId(), start, end, blankToNull(accountId), NON_SPENDING_CATEGORIES)
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

    @GetMapping("/accounts")
    public Map<String, List<AccountResponse>> getAccounts(@AuthenticationPrincipal Jwt jwt)
            throws IOException {
        User user = userService.getOrCreateUser(jwt);
        if (!accountRepository.existsByUserId(user.getId())) {
            backfillAccounts(user.getId());
        }

        List<AccountResponse> accounts = accountRepository
                .findAllByUserIdOrderByNameAscAccountIdAsc(user.getId())
                .stream()
                .map(AccountResponse::from)
                .toList();

        return Map.of("accounts", accounts);
    }

    /**
     * Items linked before accounts were stored have metadata only in Plaid. Fetch once, then
     * later loads read Postgres.
     */
    private void backfillAccounts(UUID userId) throws IOException {
        List<PlaidItem> items = plaidItemRepository.findAllByUserIdOrderByItemIdAsc(userId);
        for (PlaidItem item : items) {
            AccountsGetRequest request = new AccountsGetRequest()
                    .accessToken(tokenEncryption.decrypt(
                            item.getEncryptedAccessToken(), userId, item.getItemId()));
            Response<AccountsGetResponse> response = plaidApi.accountsGet(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid accounts get failed");
            }
            transactionSyncService.upsertAccounts(item, response.body().getAccounts());
        }
    }

    public record AccountResponse(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("balances") BalanceResponse balances,
            @JsonProperty("mask") String mask,
            @JsonProperty("name") String name,
            @JsonProperty("official_name") String officialName,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("type") String type
    ) {
        static AccountResponse from(PlaidAccount account) {
            return new AccountResponse(
                    account.getAccountId(),
                    new BalanceResponse(
                            account.getAvailableBalance(),
                            account.getCurrentBalance(),
                            account.getIsoCurrencyCode(),
                            account.getUnofficialCurrencyCode(),
                            account.getLimitAmount()
                    ),
                    account.getMask(),
                    account.getName(),
                    account.getOfficialName(),
                    account.getSubtype(),
                    account.getType()
            );
        }
    }

    public record BalanceResponse(
            @JsonProperty("available") BigDecimal available,
            @JsonProperty("current") BigDecimal current,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("unofficial_currency_code") String unofficialCurrencyCode,
            @JsonProperty("limit") BigDecimal limit
    ) {
    }

    private static int resolveRecurringLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_RECURRING_STREAMS;
        }
        return Math.min(MAX_RECURRING_STREAMS, Math.max(1, limit));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
