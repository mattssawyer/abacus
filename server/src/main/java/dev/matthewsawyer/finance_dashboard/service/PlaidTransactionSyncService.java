package dev.matthewsawyer.finance_dashboard.service;

import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class PlaidTransactionSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlaidTransactionSyncService.class);

    private static final int PAGE_SIZE = 500;

    // Plaid always advances the cursor, so this only trips if the API misbehaves.
    private static final int MAX_PAGES = 200;

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, Object> itemLocks = new ConcurrentHashMap<>();

    public PlaidTransactionSyncService(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidTransactionRepository transactionRepository,
            PlaidTokenEncryption tokenEncryption,
            TransactionTemplate transactionTemplate
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.transactionRepository = transactionRepository;
        this.tokenEncryption = tokenEncryption;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Syncs off the request thread so webhook responses stay fast. Runs one sync at a time per
     * item, since concurrent syncs would race each other's cursor.
     */
    @Async("plaidSyncExecutor")
    public void syncItemAsync(String itemId) {
        synchronized (itemLocks.computeIfAbsent(itemId, key -> new Object())) {
            try {
                PlaidItem item = plaidItemRepository.findById(itemId).orElse(null);
                if (item == null) {
                    log.warn("Skipping transactions sync for unknown item {}", itemId);
                    return;
                }
                log.info("Synced {} transaction changes for item {}", syncItem(item), itemId);
            } catch (IOException | RuntimeException e) {
                // Plaid re-notifies on the next update, so a failure here is recoverable.
                log.warn("Transactions sync failed for item {}", itemId, e);
            }
        }
    }

    /**
     * Pulls every page Plaid has for the item, starting from its stored cursor.
     * Returns the number of transactions added, modified or removed.
     */
    public int syncItem(PlaidItem item) throws IOException {
        String accessToken = tokenEncryption.decrypt(
                item.getEncryptedAccessToken(), item.getUserId(), item.getItemId());

        int changed = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            TransactionsSyncResponse body = fetchPage(accessToken, item.getTransactionsCursor());
            changed += applyPage(item, body);
            if (!Boolean.TRUE.equals(body.getHasMore())) {
                return changed;
            }
        }

        throw new IllegalStateException("Plaid transactions sync exceeded " + MAX_PAGES + " pages");
    }

    private TransactionsSyncResponse fetchPage(String accessToken, String cursor) throws IOException {
        TransactionsSyncRequest request = new TransactionsSyncRequest()
                .accessToken(accessToken)
                .cursor(cursor)
                .count(PAGE_SIZE)
                .options(new TransactionsSyncRequestOptions().includePersonalFinanceCategory(true));

        Response<TransactionsSyncResponse> response = plaidApi.transactionsSync(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid transactions sync failed");
        }

        return response.body();
    }

    /**
     * Persists one page and its cursor together, so a later page failing cannot skip
     * transactions we never stored.
     */
    private int applyPage(PlaidItem item, TransactionsSyncResponse page) {
        List<Transaction> added = Objects.requireNonNullElse(page.getAdded(), List.of());
        List<Transaction> modified = Objects.requireNonNullElse(page.getModified(), List.of());
        List<RemovedTransaction> removed = Objects.requireNonNullElse(page.getRemoved(), List.of());

        transactionTemplate.executeWithoutResult(status -> {
            List<PlaidTransaction> upserts = Stream.concat(added.stream(), modified.stream())
                    .map(transaction -> toEntity(transaction, item))
                    .toList();
            if (!upserts.isEmpty()) {
                transactionRepository.saveAll(upserts);
            }

            List<String> removedIds = removed.stream()
                    .map(RemovedTransaction::getTransactionId)
                    .toList();
            if (!removedIds.isEmpty()) {
                transactionRepository.deleteAllByItemIdAndTransactionIdIn(item.getItemId(), removedIds);
            }

            item.updateTransactionsCursor(page.getNextCursor());
            plaidItemRepository.save(item);
        });

        return added.size() + modified.size() + removed.size();
    }

    private static PlaidTransaction toEntity(Transaction transaction, PlaidItem item) {
        return new PlaidTransaction(
                transaction.getTransactionId(),
                item.getItemId(),
                item.getUserId(),
                transaction.getAccountId(),
                BigDecimal.valueOf(Objects.requireNonNullElse(transaction.getAmount(), 0.0)),
                transaction.getDate()
        )
                .isoCurrencyCode(transaction.getIsoCurrencyCode())
                .unofficialCurrencyCode(transaction.getUnofficialCurrencyCode())
                .authorizedDate(transaction.getAuthorizedDate())
                .name(transaction.getName())
                .merchantName(transaction.getMerchantName())
                .logoUrl(transaction.getLogoUrl())
                .pending(Boolean.TRUE.equals(transaction.getPending()))
                .pendingTransactionId(transaction.getPendingTransactionId())
                .paymentChannel(transaction.getPaymentChannel() == null
                        ? null : transaction.getPaymentChannel().getValue())
                .personalFinanceCategory(
                        transaction.getPersonalFinanceCategory() == null
                                ? null : transaction.getPersonalFinanceCategory().getPrimary(),
                        transaction.getPersonalFinanceCategory() == null
                                ? null : transaction.getPersonalFinanceCategory().getDetailed());
    }
}
