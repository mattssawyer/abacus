package dev.matthewsawyer.finance_dashboard.service;

import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidTransactionSyncServiceTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidTransactionRepository transactionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Call<TransactionsSyncResponse> syncCall;

    private final PlaidTokenEncryption tokenEncryption = new PlaidTokenEncryption(
            TestPlaidKeysets.create());
    private PlaidTransactionSyncService service;
    private PlaidItem item;

    @BeforeEach
    void setUp() {
        service = new PlaidTransactionSyncService(
                plaidApi,
                plaidItemRepository,
                transactionRepository,
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
    void storesAddedTransactionsAndCursorOnFirstSync() throws IOException {
        Transaction transaction = new Transaction()
                .transactionId("txn-1")
                .accountId("account-1")
                .amount(12.34)
                .isoCurrencyCode("USD")
                .date(LocalDate.of(2026, 9, 1))
                .authorizedDate(LocalDate.of(2026, 8, 31))
                .name("COFFEE SHOP")
                .merchantName("Coffee Shop")
                .pending(false)
                .paymentChannel(Transaction.PaymentChannelEnum.IN_STORE)
                .personalFinanceCategory(new PersonalFinanceCategory()
                        .primary("FOOD_AND_DRINK")
                        .detailed("FOOD_AND_DRINK_COFFEE"));

        stubSync(new TransactionsSyncResponse()
                .added(List.of(transaction))
                .nextCursor("cursor-1")
                .hasMore(false));

        assertEquals(1, service.syncItem(item));

        ArgumentCaptor<TransactionsSyncRequest> requestCaptor =
                ArgumentCaptor.forClass(TransactionsSyncRequest.class);
        verify(plaidApi).transactionsSync(requestCaptor.capture());
        assertEquals("access-token", requestCaptor.getValue().getAccessToken());
        assertNull(requestCaptor.getValue().getCursor());

        PlaidTransaction saved = captureSavedTransactions().get(0);
        assertEquals("txn-1", saved.getTransactionId());
        assertEquals("item-id", saved.getItemId());
        assertEquals(USER_ID, saved.getUserId());
        assertEquals("account-1", saved.getAccountId());
        assertEquals(0, BigDecimal.valueOf(12.34).compareTo(saved.getAmount()));
        assertEquals(LocalDate.of(2026, 9, 1), saved.getTransactionDate());
        assertEquals("Coffee Shop", saved.getMerchantName());
        assertEquals("in store", saved.getPaymentChannel());
        assertEquals("FOOD_AND_DRINK_COFFEE", saved.getPersonalFinanceCategoryDetailed());

        assertEquals("cursor-1", item.getTransactionsCursor());
        verify(plaidItemRepository).save(item);
    }

    @Test
    void followsPaginationUntilPlaidHasNoMore() throws IOException {
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(syncCall);
        when(syncCall.execute()).thenReturn(
                Response.success(new TransactionsSyncResponse()
                        .added(List.of(new Transaction()
                                .transactionId("txn-1")
                                .accountId("account-1")
                                .amount(1.0)
                                .date(LocalDate.of(2026, 9, 1))))
                        .nextCursor("cursor-1")
                        .hasMore(true)),
                Response.success(new TransactionsSyncResponse()
                        .added(List.of(new Transaction()
                                .transactionId("txn-2")
                                .accountId("account-1")
                                .amount(2.0)
                                .date(LocalDate.of(2026, 9, 2))))
                        .nextCursor("cursor-2")
                        .hasMore(false)));

        assertEquals(2, service.syncItem(item));

        ArgumentCaptor<TransactionsSyncRequest> requestCaptor =
                ArgumentCaptor.forClass(TransactionsSyncRequest.class);
        verify(plaidApi, org.mockito.Mockito.times(2)).transactionsSync(requestCaptor.capture());
        assertNull(requestCaptor.getAllValues().get(0).getCursor());
        assertEquals("cursor-1", requestCaptor.getAllValues().get(1).getCursor());
        assertEquals("cursor-2", item.getTransactionsCursor());
    }

    @Test
    void resumesFromStoredCursor() throws IOException {
        item.updateTransactionsCursor("stored-cursor");
        stubSync(new TransactionsSyncResponse().nextCursor("cursor-2").hasMore(false));

        assertEquals(0, service.syncItem(item));

        ArgumentCaptor<TransactionsSyncRequest> requestCaptor =
                ArgumentCaptor.forClass(TransactionsSyncRequest.class);
        verify(plaidApi).transactionsSync(requestCaptor.capture());
        assertEquals("stored-cursor", requestCaptor.getValue().getCursor());
        verify(transactionRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void appliesModifiedAndRemovedTransactions() throws IOException {
        stubSync(new TransactionsSyncResponse()
                .modified(List.of(new Transaction()
                        .transactionId("txn-1")
                        .accountId("account-1")
                        .amount(9.99)
                        .date(LocalDate.of(2026, 9, 1))
                        .pending(true)))
                .removed(List.of(new RemovedTransaction().transactionId("txn-old")))
                .nextCursor("cursor-2")
                .hasMore(false));

        assertEquals(2, service.syncItem(item));

        assertTrue(captureSavedTransactions().get(0).isPending());
        verify(transactionRepository).deleteAllByItemIdAndTransactionIdIn("item-id", List.of("txn-old"));
    }

    @Test
    void failsWithBadGatewayWhenPlaidRejectsSync() throws IOException {
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(syncCall);
        when(syncCall.execute()).thenReturn(
                Response.error(500, ResponseBody.create("{}", MediaType.get("application/json"))));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.syncItem(item));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertNull(item.getTransactionsCursor());
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void asyncSyncStoresTransactionsForTheStoredItem() throws IOException {
        when(plaidItemRepository.findById("item-id")).thenReturn(Optional.of(item));
        stubSync(new TransactionsSyncResponse()
                .added(List.of(new Transaction()
                        .transactionId("txn-1")
                        .accountId("account-1")
                        .amount(1.0)
                        .date(LocalDate.of(2026, 9, 1))))
                .nextCursor("cursor-1")
                .hasMore(false));

        service.syncItemAsync("item-id");

        assertEquals("txn-1", captureSavedTransactions().get(0).getTransactionId());
        assertEquals("cursor-1", item.getTransactionsCursor());
    }

    @Test
    void asyncSyncSwallowsFailuresSoPlaidIsNotRetriedForever() throws IOException {
        when(plaidItemRepository.findById("item-id")).thenReturn(Optional.of(item));
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(syncCall);
        when(syncCall.execute()).thenThrow(new IOException("Plaid unreachable"));

        service.syncItemAsync("item-id");

        verify(transactionRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void asyncSyncIgnoresItemsThatAreNoLongerStored() {
        when(plaidItemRepository.findById("missing-item")).thenReturn(Optional.empty());

        service.syncItemAsync("missing-item");

        verifyNoMoreInteractions(plaidApi);
    }

    @Test
    void refusesPlaintextStoredTokenBeforeCallingPlaid() {
        PlaidItem plaintextItem = new PlaidItem("item-id", "access-token", USER_ID);

        assertThrows(IllegalStateException.class, () -> service.syncItem(plaintextItem));

        verifyNoMoreInteractions(plaidApi);
    }

    private void stubSync(TransactionsSyncResponse response) throws IOException {
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(syncCall);
        when(syncCall.execute()).thenReturn(Response.success(response));
    }

    @SuppressWarnings("unchecked")
    private List<PlaidTransaction> captureSavedTransactions() {
        ArgumentCaptor<List<PlaidTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
