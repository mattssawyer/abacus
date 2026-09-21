package dev.matthewsawyer.finance_dashboard.controller;

import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.service.PlaidTokenEncryption;
import dev.matthewsawyer.finance_dashboard.service.PlaidTransactionSyncService;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidControllerTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String WEBHOOK_URL = "https://abacus.test/api/plaid/webhook";

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidAccountRepository accountRepository;

    @Mock
    private PlaidTransactionRepository transactionRepository;

    @Mock
    private UserService userService;

    @Mock
    private PlaidTransactionSyncService transactionSyncService;

    @Mock
    private Call<LinkTokenCreateResponse> linkTokenCall;

    @Mock
    private Call<ItemPublicTokenExchangeResponse> exchangeCall;

    @Mock
    private Call<AccountsGetResponse> accountsCall;

    private final PlaidTokenEncryption tokenEncryption = new PlaidTokenEncryption(
            TestPlaidKeysets.create());
    private PlaidController controller;
    private Jwt jwt;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController(
                plaidApi,
                plaidItemRepository,
                accountRepository,
                transactionRepository,
                userService,
                tokenEncryption,
                transactionSyncService,
                WEBHOOK_URL
        );
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("clerk-user")
                .build();
        user = new User("clerk-user");
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void pointsLinkTokensAtTheWebhookUrl() throws IOException {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidApi.linkTokenCreate(any(LinkTokenCreateRequest.class))).thenReturn(linkTokenCall);
        when(linkTokenCall.execute()).thenReturn(
                Response.success(new LinkTokenCreateResponse().linkToken("link-token")));

        assertEquals(Map.of("link_token", "link-token"), controller.createLinkToken(jwt));

        ArgumentCaptor<LinkTokenCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidApi).linkTokenCreate(requestCaptor.capture());
        assertEquals(WEBHOOK_URL, requestCaptor.getValue().getWebhook());
    }

    @Test
    void omitsTheWebhookUrlWhenItIsNotConfigured() throws IOException {
        PlaidController unconfigured = new PlaidController(
                plaidApi,
                plaidItemRepository,
                accountRepository,
                transactionRepository,
                userService,
                tokenEncryption,
                transactionSyncService,
                ""
        );
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidApi.linkTokenCreate(any(LinkTokenCreateRequest.class))).thenReturn(linkTokenCall);
        when(linkTokenCall.execute()).thenReturn(
                Response.success(new LinkTokenCreateResponse().linkToken("link-token")));

        unconfigured.createLinkToken(jwt);

        ArgumentCaptor<LinkTokenCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidApi).linkTokenCreate(requestCaptor.capture());
        assertNull(requestCaptor.getValue().getWebhook());
    }

    @Test
    void rejectsBlankPublicToken() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.exchangePublicToken(
                        jwt,
                        new PlaidController.ExchangePublicTokenRequest(" ")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(userService, plaidApi, plaidItemRepository, transactionSyncService);
    }

    @Test
    void exchangesPublicTokenAndStoresEncryptedAccessToken() throws IOException {
        ItemPublicTokenExchangeResponse plaidResponse = new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken("access-token")
                .requestId("request-id");

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(plaidResponse));
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.empty());

        Map<String, String> result = controller.exchangePublicToken(
                jwt, new PlaidController.ExchangePublicTokenRequest("public-token"));

        ArgumentCaptor<ItemPublicTokenExchangeRequest> requestCaptor =
                ArgumentCaptor.forClass(ItemPublicTokenExchangeRequest.class);
        ArgumentCaptor<PlaidItem> itemCaptor = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidApi).itemPublicTokenExchange(requestCaptor.capture());
        verify(plaidItemRepository).save(itemCaptor.capture());

        assertEquals("public-token", requestCaptor.getValue().getPublicToken());
        assertEquals(Map.of("item_id", "item-id"), result);
        assertEquals("item-id", itemCaptor.getValue().getItemId());
        String encrypted = itemCaptor.getValue().getEncryptedAccessToken();
        assertNotEquals("access-token", encrypted);
        assertEquals("access-token", tokenEncryption.decrypt(encrypted, USER_ID, "item-id"));
        assertEquals(USER_ID, itemCaptor.getValue().getUserId());
        verify(transactionSyncService).syncItem(itemCaptor.getValue());
    }

    @Test
    void keepsExistingCursorWhenItemIsRelinked() throws IOException {
        PlaidItem existingItem = new PlaidItem("item-id", "old-encrypted-token", USER_ID);
        existingItem.updateTransactionsCursor("stored-cursor");
        ItemPublicTokenExchangeResponse plaidResponse = new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken("new-access-token")
                .requestId("request-id");

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(plaidResponse));
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID))
                .thenReturn(Optional.of(existingItem));

        controller.exchangePublicToken(
                jwt, new PlaidController.ExchangePublicTokenRequest("public-token"));

        ArgumentCaptor<PlaidItem> itemCaptor = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidItemRepository).save(itemCaptor.capture());

        assertEquals("stored-cursor", itemCaptor.getValue().getTransactionsCursor());
        assertEquals("new-access-token", tokenEncryption.decrypt(
                itemCaptor.getValue().getEncryptedAccessToken(), USER_ID, "item-id"));
    }

    @Test
    void linksItemEvenWhenInitialTransactionSyncFails() throws IOException {
        ItemPublicTokenExchangeResponse plaidResponse = new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken("access-token")
                .requestId("request-id");

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(plaidResponse));
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.empty());
        when(transactionSyncService.syncItem(any(PlaidItem.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom"));

        Map<String, String> result = controller.exchangePublicToken(
                jwt, new PlaidController.ExchangePublicTokenRequest("public-token"));

        assertEquals(Map.of("item_id", "item-id"), result);
        verify(plaidItemRepository).save(any(PlaidItem.class));
    }

    @Test
    void doesNotStoreItemWhenPlaidExchangeFails() throws IOException {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(
                Response.error(500, ResponseBody.create("{}", MediaType.get("application/json"))));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.exchangePublicToken(
                        jwt, new PlaidController.ExchangePublicTokenRequest("public-token"))
        );

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        verifyNoInteractions(plaidItemRepository);
    }

    @Test
    void listsOnlyItemIdsForCurrentUser() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findAllByUserIdOrderByItemIdAsc(USER_ID)).thenReturn(List.of(
                new PlaidItem("item-one", "encrypted-token-one", USER_ID),
                new PlaidItem("item-two", "encrypted-token-two", USER_ID)
        ));

        Map<String, List<String>> result = controller.getLinkedItems(jwt);

        assertEquals(Map.of("item_ids", List.of("item-one", "item-two")), result);
        verify(plaidItemRepository).findAllByUserIdOrderByItemIdAsc(USER_ID);
        verifyNoInteractions(plaidApi);
    }

    @Test
    void returnsEmptyItemsForUserWithoutConnections() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findAllByUserIdOrderByItemIdAsc(USER_ID)).thenReturn(List.of());

        assertEquals(Map.of("item_ids", List.of()), controller.getLinkedItems(jwt));
        verifyNoInteractions(plaidApi);
    }

    @Test
    void returnsStoredAccountsWithoutCallingPlaid() throws IOException {
        PlaidAccount stored = checkingAccount();
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.existsByUserId(USER_ID)).thenReturn(true);
        when(accountRepository.findAllByUserIdOrderByNameAscAccountIdAsc(USER_ID))
                .thenReturn(List.of(stored));

        List<PlaidController.AccountResponse> result = controller.getAccounts(jwt).get("accounts");

        assertEquals(1, result.size());
        assertEquals("checking", result.get(0).accountId());
        assertEquals("Checking", result.get(0).name());
        assertEquals(new BigDecimal("1250.50"), result.get(0).balances().current());
        verifyNoInteractions(plaidApi);
    }

    @Test
    void backfillsAccountsFromPlaidWhenNoneAreStored() throws IOException {
        PlaidItem item = new PlaidItem(
                "item-id", tokenEncryption.encrypt("access-token", USER_ID, "item-id"), USER_ID);
        AccountsGetResponse plaidResponse = new AccountsGetResponse()
                .accounts(List.of(new AccountBase()
                        .accountId("checking")
                        .name("Checking")
                        .type(AccountType.DEPOSITORY)
                        .balances(new AccountBalance().current(1250.5))));

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.existsByUserId(USER_ID)).thenReturn(false);
        when(plaidItemRepository.findAllByUserIdOrderByItemIdAsc(USER_ID)).thenReturn(List.of(item));
        when(plaidApi.accountsGet(any(AccountsGetRequest.class))).thenReturn(accountsCall);
        when(accountsCall.execute()).thenReturn(Response.success(plaidResponse));
        when(accountRepository.findAllByUserIdOrderByNameAscAccountIdAsc(USER_ID))
                .thenReturn(List.of(checkingAccount()));

        controller.getAccounts(jwt);

        ArgumentCaptor<AccountsGetRequest> requestCaptor =
                ArgumentCaptor.forClass(AccountsGetRequest.class);
        verify(plaidApi).accountsGet(requestCaptor.capture());
        assertEquals("access-token", requestCaptor.getValue().getAccessToken());
        verify(transactionSyncService).upsertAccounts(item, plaidResponse.getAccounts());
    }

    @Test
    void refusesPlaintextStoredTokenBeforeCallingPlaid() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.existsByUserId(USER_ID)).thenReturn(false);
        when(plaidItemRepository.findAllByUserIdOrderByItemIdAsc(USER_ID)).thenReturn(List.of(
                new PlaidItem("item-id", "access-token", USER_ID)));

        assertThrows(IllegalStateException.class, () -> controller.getAccounts(jwt));
        verifyNoInteractions(plaidApi);
    }

    @Test
    void returnsRecentTransactionsForCurrentUser() {
        PlaidTransaction stored = new PlaidTransaction(
                "txn-1", "item-id", USER_ID, "account-1",
                new BigDecimal("12.34"), LocalDate.of(2026, 9, 1))
                .merchantName("Coffee Shop")
                .name("COFFEE SHOP")
                .isoCurrencyCode("USD")
                .personalFinanceCategory("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE");

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(transactionRepository.findRecent(USER_ID, null, Pageable.ofSize(5)))
                .thenReturn(List.of(stored));

        List<PlaidController.TransactionResponse> result =
                controller.getTransactions(jwt, 5, null).get("transactions");

        assertEquals(1, result.size());
        assertEquals("txn-1", result.get(0).transactionId());
        assertEquals("Coffee Shop", result.get(0).merchantName());
        assertEquals(LocalDate.of(2026, 9, 1), result.get(0).date());
        assertEquals("FOOD_AND_DRINK", result.get(0).category());
        verifyNoInteractions(plaidApi);
    }

    @Test
    void totalsSpendingByCategoryForTheCurrentMonth() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(transactionRepository.sumSpendingByCategory(
                eq(USER_ID), any(), any(), isNull(), anyCollection()))
                .thenReturn(List.of(
                        categoryTotal("FOOD_AND_DRINK", "82.50"),
                        categoryTotal("RENT_AND_UTILITIES", "1450.00"),
                        categoryTotal("UNCATEGORIZED", "12.00")));

        PlaidController.SpendingByCategoryResponse result = controller.getSpendingByCategory(jwt, null);

        LocalDate expectedStart = LocalDate.now().withDayOfMonth(1);
        assertEquals(expectedStart, result.start());
        assertEquals(expectedStart.withDayOfMonth(expectedStart.lengthOfMonth()), result.end());
        assertEquals(new BigDecimal("1544.50"), result.total());
        assertEquals(
                List.of("RENT_AND_UTILITIES", "FOOD_AND_DRINK", "UNCATEGORIZED"),
                result.categories().stream().map(PlaidController.CategorySpend::category).toList());
    }

    @Test
    void leavesIncomeAndTransfersOutOfTheSpendingQuery() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(transactionRepository.sumSpendingByCategory(
                eq(USER_ID), any(), any(), isNull(), anyCollection()))
                .thenReturn(List.of());

        controller.getSpendingByCategory(jwt, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> excludedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository).sumSpendingByCategory(
                eq(USER_ID), any(), any(), isNull(), excludedCaptor.capture());

        assertEquals(
                Set.of("INCOME", "TRANSFER_IN", "TRANSFER_OUT"),
                Set.copyOf(excludedCaptor.getValue()));
    }

    @Test
    void dropsCategoriesRefundedBackToZero() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(transactionRepository.sumSpendingByCategory(
                eq(USER_ID), any(), any(), isNull(), anyCollection()))
                .thenReturn(List.of(
                        categoryTotal("GENERAL_MERCHANDISE", "-25.00"),
                        categoryTotal("MEDICAL", "0.00"),
                        categoryTotal("TRAVEL", "300.00")));

        PlaidController.SpendingByCategoryResponse result = controller.getSpendingByCategory(jwt, null);

        assertEquals(
                List.of("TRAVEL"),
                result.categories().stream().map(PlaidController.CategorySpend::category).toList());
        assertEquals(new BigDecimal("300.00"), result.total());
    }

    private static PlaidTransactionRepository.CategoryTotal categoryTotal(String category, String total) {
        return new PlaidTransactionRepository.CategoryTotal() {

            @Override
            public String getCategory() {
                return category;
            }

            @Override
            public BigDecimal getTotal() {
                return new BigDecimal(total);
            }
        };
    }

    @Test
    void rejectsOutOfRangeTransactionLimit() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.getTransactions(jwt, 101, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(userService, transactionRepository, plaidApi);
    }

    @Test
    void filtersRecentTransactionsToTheRequestedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(transactionRepository.findRecent(USER_ID, "account-1", Pageable.ofSize(5)))
                .thenReturn(List.of());

        controller.getTransactions(jwt, 5, "account-1");

        verify(transactionRepository).findRecent(USER_ID, "account-1", Pageable.ofSize(5));
    }

    @Test
    void filtersSpendingToTheRequestedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(transactionRepository.sumSpendingByCategory(
                eq(USER_ID), any(), any(), eq("account-1"), anyCollection()))
                .thenReturn(List.of());

        controller.getSpendingByCategory(jwt, "account-1");

        verify(transactionRepository).sumSpendingByCategory(
                eq(USER_ID), any(), any(), eq("account-1"), anyCollection());
    }

    private static PlaidAccount checkingAccount() {
        PlaidAccount account = new PlaidAccount("checking", "item-id", USER_ID);
        account.updateSnapshot(
                "Checking",
                null,
                "1234",
                "depository",
                "checking",
                new BigDecimal("1200.00"),
                new BigDecimal("1250.50"),
                null,
                "USD",
                null
        );
        return account;
    }
}
