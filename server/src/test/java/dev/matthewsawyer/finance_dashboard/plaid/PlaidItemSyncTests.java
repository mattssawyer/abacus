package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class PlaidItemSyncTests {

    private static final String ITEM_ID = "sync-test-item";

    @MockitoBean private PlaidApi plaidApi;

    @Autowired private TransactionsSync transactionsSync;
    @Autowired private RecurringStreamsSync recurringStreamsSync;
    @Autowired private PlaidTokenEncryption tokenEncryption;
    @Autowired private PlaidItemRepository items;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private PlaidRecurringStreamRepository streams;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    /** Webhook syncs are queued here and run when the test says so. */
    private final List<Runnable> queued = new ArrayList<>();
    private final BucketSorting bucketSorting = mock(BucketSorting.class);
    private PlaidItemSync itemSync;
    private UUID userId;

    @BeforeEach
    void setUp() {
        itemSync = new PlaidItemSync(
                items, transactionsSync, recurringStreamsSync, bucketSorting, queued::add);
        userId = users.saveAndFlush(new User("item-sync-test-user")).getId();
        items.saveAndFlush(new PlaidItem(
                ITEM_ID, tokenEncryption.encrypt("access-token", userId, ITEM_ID), userId));
        entityManager.clear();
    }

    @Test
    void storesAccountsTransactionsAndStreamsWhenAnItemIsLinked() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        itemSync.linked(storedItem());

        verify(bucketSorting).sortLater(userId);
        assertEquals(List.of("checking"), accountIds());
        assertEquals(List.of("txn-1"), transactionIds());
        assertEquals(List.of("rent"), streamIds());
        PlaidItem item = storedItem();
        assertEquals("cursor-1", item.getTransactionsCursor());
        assertNotNull(item.getRecurringSyncedAt());
    }

    @Test
    void stillSyncsStreamsWhenTheTransactionsSyncFails() throws IOException {
        stubTransactionsSyncFailure();
        stubRecurring(recurringResponse());

        itemSync.linked(storedItem());

        assertTrue(transactionIds().isEmpty());
        assertEquals(List.of("rent"), streamIds());
    }

    @Test
    void queuesBothSyncsWhenPlaidReportsNewTransactions() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        itemSync.notified(ITEM_ID, "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");
        verifyNoInteractions(plaidApi);
        runQueued();

        assertEquals(List.of("txn-1"), transactionIds());
        assertEquals(List.of("rent"), streamIds());
    }

    @Test
    void syncsOnlyStreamsWhenPlaidReportsARecurringUpdate() throws IOException {
        stubRecurring(recurringResponse());

        itemSync.notified(ITEM_ID, "RECURRING_TRANSACTIONS", "RECURRING_TRANSACTIONS_UPDATE");
        runQueued();

        assertEquals(List.of("rent"), streamIds());
        verify(plaidApi, never()).transactionsSync(any());
        verifyNoInteractions(bucketSorting);
    }

    @Test
    void ignoresWebhooksThatNeedNoSync() {
        itemSync.notified(ITEM_ID, "ITEM", "ERROR");
        // The legacy /transactions/get integration; this app uses /transactions/sync.
        itemSync.notified(ITEM_ID, "TRANSACTIONS", "DEFAULT_UPDATE");
        itemSync.notified(null, "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");

        assertTrue(queued.isEmpty());
    }

    @Test
    void ignoresWebhooksForItemsWeDoNotStore() {
        itemSync.notified("unknown-item", "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");
        runQueued();

        verifyNoInteractions(plaidApi);
    }

    @Test
    void recurringSyncHoldingAnOlderCopyOfTheItemKeepsTheNewerCursor() throws IOException {
        PlaidItem olderCopy = storedItem();
        entityManager.detach(olderCopy);
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        transactionsSync.sync(storedItem());
        recurringStreamsSync.sync(olderCopy);

        PlaidItem item = storedItem();
        assertEquals("cursor-1", item.getTransactionsCursor());
        assertNotNull(item.getRecurringSyncedAt());
    }

    private void runQueued() {
        List<Runnable> tasks = List.copyOf(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    /** Reads the item back from the database, after writing out anything still pending. */
    private PlaidItem storedItem() {
        entityManager.flush();
        entityManager.clear();
        return items.findById(ITEM_ID).orElseThrow();
    }

    private List<String> accountIds() {
        return accounts.findAllByUserIdOrderByNameAscAccountIdAsc(userId).stream()
                .map(account -> account.getAccountId())
                .toList();
    }

    private List<String> transactionIds() {
        return transactions.findRecent(userId, null, Pageable.ofSize(10)).stream()
                .map(transaction -> transaction.getTransactionId())
                .toList();
    }

    private List<String> streamIds() {
        return streams.findAllByUserId(userId).stream()
                .map(PlaidRecurringStream::getStreamId)
                .toList();
    }

    private static TransactionsSyncResponse transactionsPage(String nextCursor) {
        return new TransactionsSyncResponse()
                .accounts(List.of(new AccountBase()
                        .accountId("checking")
                        .name("Checking")
                        .type(AccountType.DEPOSITORY)))
                .added(List.of(new Transaction()
                        .transactionId("txn-1")
                        .accountId("checking")
                        .amount(12.34)
                        .date(LocalDate.of(2026, 9, 1))))
                .nextCursor(nextCursor)
                .hasMore(false);
    }

    private static TransactionsRecurringGetResponse recurringResponse() {
        return new TransactionsRecurringGetResponse()
                .outflowStreams(List.of(new TransactionStream()
                        .streamId("rent")
                        .accountId("checking")
                        .merchantName("Landlord")
                        .lastAmount(new TransactionStreamAmount().amount(1450.0))
                        .frequency(RecurringTransactionFrequency.MONTHLY)
                        .isActive(true)))
                .inflowStreams(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionsSync(TransactionsSyncResponse response) throws IOException {
        Call<TransactionsSyncResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(response));
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionsSyncFailure() throws IOException {
        Call<TransactionsSyncResponse> call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("Plaid unreachable"));
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubRecurring(TransactionsRecurringGetResponse response) throws IOException {
        Call<TransactionsRecurringGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(response));
        when(plaidApi.transactionsRecurringGet(any(TransactionsRecurringGetRequest.class)))
                .thenReturn(call);
    }
}
