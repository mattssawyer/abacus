package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidItemLinking;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock
    private PlaidItemLinking itemLinking;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidAccountRepository accountRepository;

    @Mock
    private PlaidTransactionRepository transactionRepository;

    @Mock
    private PlaidRecurringStreamRepository recurringStreamRepository;

    @Mock
    private UserService userService;

    private PlaidController controller;
    private Jwt jwt;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController(
                itemLinking,
                plaidItemRepository,
                accountRepository,
                transactionRepository,
                recurringStreamRepository,
                userService
        );
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("clerk-user")
                .build();
        user = new User("clerk-user");
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void createsLinkTokensForTheCurrentUser() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.createLinkToken(USER_ID)).thenReturn("link-token");

        assertEquals(Map.of("link_token", "link-token"), controller.createLinkToken(jwt));
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
        verifyNoInteractions(userService, itemLinking);
    }

    @Test
    void linksTheItemForTheCurrentUser() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.link(USER_ID, "public-token")).thenReturn("item-id");

        Map<String, String> result = controller.exchangePublicToken(
                jwt, new PlaidController.ExchangePublicTokenRequest("public-token"));

        assertEquals(Map.of("item_id", "item-id"), result);
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
    }

    @Test
    void returnsEmptyItemsForUserWithoutConnections() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findAllByUserIdOrderByItemIdAsc(USER_ID)).thenReturn(List.of());

        assertEquals(Map.of("item_ids", List.of()), controller.getLinkedItems(jwt));
    }

    @Test
    void returnsStoredAccounts() {
        PlaidAccount stored = checkingAccount();
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findAllByUserIdOrderByNameAscAccountIdAsc(USER_ID))
                .thenReturn(List.of(stored));

        List<PlaidController.AccountResponse> result = controller.getAccounts(jwt).get("accounts");

        assertEquals(1, result.size());
        assertEquals("checking", result.get(0).accountId());
        assertEquals("Checking", result.get(0).name());
        assertEquals(new BigDecimal("1250.50"), result.get(0).balances().current());
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
        verifyNoInteractions(userService, transactionRepository);
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
    void returnsStoredRecurringStreamsSoonestFirst() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(recurringStreamRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                storedStream("rent", "checking", "Landlord", new BigDecimal("1450.0"), "MONTHLY",
                        LocalDate.of(2026, 10, 1), false),
                storedStream("later", "checking", "Netflix", new BigDecimal("15.49"), "MONTHLY",
                        LocalDate.of(2026, 10, 12), false),
                storedStream("pay", "checking", "Payroll", new BigDecimal("-2400.0"), "BIWEEKLY",
                        LocalDate.of(2026, 9, 25), true)));

        List<PlaidController.RecurringStreamResponse> streams =
                controller.getRecurringTransactions(jwt, null, null).get("streams");

        assertEquals(List.of("pay", "rent", "later"), streams.stream()
                .map(PlaidController.RecurringStreamResponse::streamId)
                .toList());
        assertEquals(new BigDecimal("1450.0"), streams.get(1).amount());
        assertEquals("MONTHLY", streams.get(1).frequency());
        assertTrue(streams.get(0).isInflow());
        assertEquals("FOOD_AND_DRINK", streams.get(1).category());
        assertEquals("FOOD_AND_DRINK_GROCERIES", streams.get(1).categoryDetailed());
    }

    @Test
    void filtersStoredRecurringStreamsToTheRequestedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(recurringStreamRepository.findAllByUserIdAndAccountId(USER_ID, "account-1"))
                .thenReturn(List.of());

        controller.getRecurringTransactions(jwt, "account-1", null);

        verify(recurringStreamRepository).findAllByUserIdAndAccountId(USER_ID, "account-1");
    }

    @Test
    void returnsNoRecurringStreamsWhenNothingIsLinked() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(recurringStreamRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        assertEquals(Map.of("streams", List.of()), controller.getRecurringTransactions(jwt, null, null));
    }

    @Test
    void capsRecurringStreamsAtTheRequestedLimit() {
        List<PlaidRecurringStream> stored = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            stored.add(storedStream(
                    "bill-" + i,
                    "checking",
                    "Bill " + i,
                    BigDecimal.valueOf(10.0 * i),
                    "MONTHLY",
                    LocalDate.of(2026, 10, i),
                    false));
        }
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(recurringStreamRepository.findAllByUserId(USER_ID)).thenReturn(stored);

        assertEquals(8, controller.getRecurringTransactions(jwt, null, null).get("streams").size());
        assertEquals(12, controller.getRecurringTransactions(jwt, null, 50).get("streams").size());
        assertEquals(1, controller.getRecurringTransactions(jwt, null, 1).get("streams").size());
    }

    private static PlaidRecurringStream storedStream(
            String streamId,
            String accountId,
            String merchant,
            BigDecimal amount,
            String frequency,
            LocalDate nextDate,
            boolean inflow
    ) {
        return new PlaidRecurringStream(
                streamId, "item-id", USER_ID, accountId, amount, frequency, inflow)
                .merchantName(merchant)
                .description(merchant.toUpperCase())
                .isoCurrencyCode("USD")
                .nextDate(nextDate)
                .lastDate(nextDate.minusMonths(1))
                .category("FOOD_AND_DRINK")
                .categoryDetailed("FOOD_AND_DRINK_GROCERIES");
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
