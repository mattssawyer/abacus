package dev.matthewsawyer.finance_dashboard.plaid;

import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Keeps a Plaid item's stored accounts, transactions and recurring streams up to date.
 *
 * <p>Syncs of the same item never overlap: concurrent syncs would race each other's
 * transactions cursor. Failures are logged rather than thrown, because Plaid notifies again on
 * its next update and that sync picks up whatever this one missed.
 */
@Service
public class PlaidItemSync {

    private static final Logger log = LoggerFactory.getLogger(PlaidItemSync.class);

    private static final String TRANSACTIONS = "TRANSACTIONS";
    private static final String SYNC_UPDATES_AVAILABLE = "SYNC_UPDATES_AVAILABLE";
    private static final String RECURRING_TRANSACTIONS = "RECURRING_TRANSACTIONS";
    private static final String RECURRING_TRANSACTIONS_UPDATE = "RECURRING_TRANSACTIONS_UPDATE";

    private final PlaidItemRepository plaidItemRepository;
    private final TransactionsSync transactionsSync;
    private final RecurringStreamsSync recurringStreamsSync;
    private final Executor executor;
    private final Map<String, Object> itemLocks = new ConcurrentHashMap<>();

    PlaidItemSync(
            PlaidItemRepository plaidItemRepository,
            TransactionsSync transactionsSync,
            RecurringStreamsSync recurringStreamsSync,
            @Qualifier("plaidSyncExecutor") Executor executor
    ) {
        this.plaidItemRepository = plaidItemRepository;
        this.transactionsSync = transactionsSync;
        this.recurringStreamsSync = recurringStreamsSync;
        this.executor = executor;
    }

    /** Syncs a newly linked or relinked item before returning, so its data is there right away. */
    public void linked(PlaidItem item) {
        syncUnderLock(item.getItemId(), true);
    }

    /**
     * Handles a Plaid webhook for an item. Syncs run off the calling thread so webhook responses
     * stay fast; webhooks that need no sync, and items we don't store, are ignored.
     */
    public void notified(String itemId, String webhookType, String webhookCode) {
        boolean transactions = TRANSACTIONS.equals(webhookType)
                && SYNC_UPDATES_AVAILABLE.equals(webhookCode);
        boolean recurring = RECURRING_TRANSACTIONS.equals(webhookType)
                && RECURRING_TRANSACTIONS_UPDATE.equals(webhookCode);

        if (!transactions && !recurring) {
            log.info("Ignoring Plaid webhook {}/{} for item {}", webhookType, webhookCode, itemId);
            return;
        }
        if (itemId == null) {
            log.warn("Ignoring Plaid webhook {}/{} without an item", webhookType, webhookCode);
            return;
        }

        log.info("Queuing sync for item {} after Plaid webhook {}/{}", itemId, webhookType, webhookCode);
        executor.execute(() -> syncUnderLock(itemId, transactions));
    }

    /** Recurring streams always sync: new transactions can change which streams Plaid detects. */
    private void syncUnderLock(String itemId, boolean transactions) {
        synchronized (itemLocks.computeIfAbsent(itemId, key -> new Object())) {
            // Load inside the lock so the sync starts from what the previous one stored.
            PlaidItem item = plaidItemRepository.findById(itemId).orElse(null);
            if (item == null) {
                log.warn("Skipping sync for unknown item {}", itemId);
                return;
            }
            if (transactions) {
                run("Transactions", item, () -> transactionsSync.sync(item));
            }
            run("Recurring stream", item, () -> recurringStreamsSync.sync(item));
        }
    }

    private static void run(String kind, PlaidItem item, Runnable sync) {
        try {
            sync.run();
        } catch (RuntimeException e) {
            log.warn("{} sync failed for item {}", kind, item.getItemId(), e);
        }
    }
}
