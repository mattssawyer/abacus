package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringStreamsSyncTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidRecurringStreamRepository streamRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Call<TransactionsRecurringGetResponse> recurringCall;

    private final PlaidTokenEncryption tokenEncryption = new PlaidTokenEncryption(
            TestPlaidKeysets.create());
    private RecurringStreamsSync sync;
    private PlaidItem item;

    @BeforeEach
    void setUp() {
        sync = new RecurringStreamsSync(
                plaidApi,
                plaidItemRepository,
                streamRepository,
                tokenEncryption,
                new TransactionTemplate(transactionManager)
        );
        item = new PlaidItem(
                "item-id",
                tokenEncryption.encrypt("access-token", USER_ID, "item-id"),
                USER_ID
        );
    }

    @Test
    void storesNamedBillsAndPaychecksAndDropsInterest() throws IOException {
        stubRecurring(new TransactionsRecurringGetResponse()
                .outflowStreams(List.of(
                        stream("rent", "checking", "Landlord", 1450.0, RecurringTransactionFrequency.MONTHLY,
                                LocalDate.of(2026, 10, 1), true),
                        stream("inactive", "checking", "Old Gym", 30.0, RecurringTransactionFrequency.MONTHLY,
                                LocalDate.of(2026, 9, 22), false)))
                .inflowStreams(List.of(
                        stream("pay", "checking", "Payroll", -2400.0, RecurringTransactionFrequency.BIWEEKLY,
                                LocalDate.of(2026, 9, 25), true),
                        unnamedInterestDeposit(),
                        interestPaymentDeposit())));

        sync.sync(item);

        ArgumentCaptor<TransactionsRecurringGetRequest> requestCaptor =
                ArgumentCaptor.forClass(TransactionsRecurringGetRequest.class);
        verify(plaidApi).transactionsRecurringGet(requestCaptor.capture());
        assertEquals("access-token", requestCaptor.getValue().getAccessToken());
        assertNull(requestCaptor.getValue().getAccountIds());

        verify(streamRepository).deleteAllByItemId("item-id");
        List<PlaidRecurringStream> stored = captureSavedStreams();
        assertEquals(List.of("rent", "pay"), stored.stream().map(PlaidRecurringStream::getStreamId).toList());
        assertEquals(0, new BigDecimal("1450.0").compareTo(stored.get(0).getAmount()));
        assertEquals("MONTHLY", stored.get(0).getFrequency());
        assertEquals("FOOD_AND_DRINK", stored.get(0).getCategory());
        assertEquals("FOOD_AND_DRINK_GROCERIES", stored.get(0).getCategoryDetailed());
        assertTrue(stored.get(1).isInflow());
        verify(plaidItemRepository).markRecurringSynced(eq("item-id"), any(Instant.class));
    }

    @Test
    void replacesPreviouslyStoredStreamsForTheItem() throws IOException {
        stubRecurring(new TransactionsRecurringGetResponse()
                .outflowStreams(List.of())
                .inflowStreams(List.of()));

        sync.sync(item);

        verify(streamRepository).deleteAllByItemId("item-id");
        verify(streamRepository, org.mockito.Mockito.never()).saveAll(any());
        verify(plaidItemRepository).markRecurringSynced(eq("item-id"), any(Instant.class));
    }

    @Test
    void failsWithoutTouchingStoredStreamsWhenPlaidRejectsTheFetch() throws IOException {
        when(plaidApi.transactionsRecurringGet(any(TransactionsRecurringGetRequest.class)))
                .thenReturn(recurringCall);
        when(recurringCall.execute()).thenReturn(
                Response.error(500, ResponseBody.create("{}", MediaType.get("application/json"))));

        assertThrows(PlaidRequestException.class, () -> sync.sync(item));

        verifyNoMoreInteractions(streamRepository, plaidItemRepository);
    }

    private void stubRecurring(TransactionsRecurringGetResponse response) throws IOException {
        when(plaidApi.transactionsRecurringGet(any(TransactionsRecurringGetRequest.class)))
                .thenReturn(recurringCall);
        when(recurringCall.execute()).thenReturn(Response.success(response));
    }

    @SuppressWarnings("unchecked")
    private List<PlaidRecurringStream> captureSavedStreams() {
        ArgumentCaptor<List<PlaidRecurringStream>> captor = ArgumentCaptor.forClass(List.class);
        verify(streamRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static TransactionStream stream(
            String streamId,
            String accountId,
            String merchant,
            double amount,
            RecurringTransactionFrequency frequency,
            LocalDate nextDate,
            boolean active
    ) {
        return new TransactionStream()
                .streamId(streamId)
                .accountId(accountId)
                .merchantName(merchant)
                .description(merchant.toUpperCase())
                .lastAmount(new TransactionStreamAmount().amount(amount).isoCurrencyCode("USD"))
                .frequency(frequency)
                .predictedNextDate(nextDate)
                .lastDate(nextDate.minusMonths(1))
                .isActive(active)
                .personalFinanceCategory(new PersonalFinanceCategory()
                        .primary("FOOD_AND_DRINK")
                        .detailed("FOOD_AND_DRINK_GROCERIES"));
    }

    private static TransactionStream unnamedInterestDeposit() {
        return new TransactionStream()
                .streamId("interest")
                .accountId("checking")
                .lastAmount(new TransactionStreamAmount().amount(-0.12).isoCurrencyCode("USD"))
                .frequency(RecurringTransactionFrequency.MONTHLY)
                .predictedNextDate(LocalDate.of(2026, 10, 1))
                .lastDate(LocalDate.of(2026, 9, 1))
                .isActive(true)
                .personalFinanceCategory(new PersonalFinanceCategory()
                        .primary("INCOME")
                        .detailed("INCOME_INTEREST_EARNED"));
    }

    private static TransactionStream interestPaymentDeposit() {
        return new TransactionStream()
                .streamId("interest-label")
                .accountId("checking")
                .description("INTEREST PAYMENT")
                .lastAmount(new TransactionStreamAmount().amount(-0.12).isoCurrencyCode("USD"))
                .frequency(RecurringTransactionFrequency.MONTHLY)
                .predictedNextDate(LocalDate.of(2026, 10, 1))
                .lastDate(LocalDate.of(2026, 9, 1))
                .isActive(true);
    }
}
