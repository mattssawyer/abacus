package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Brings an item's accounts and transactions up to date, starting from its stored cursor. */
@Component
class TransactionsSync {

    private static final Logger log = LoggerFactory.getLogger(TransactionsSync.class);

    private static final int PAGE_SIZE = 500;

    // Plaid always advances the cursor, so this only trips if the API misbehaves.
    private static final int MAX_PAGES = 200;

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidAccountRepository accountRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final TransactionTemplate transactionTemplate;

    TransactionsSync(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidAccountRepository accountRepository,
            PlaidTransactionRepository transactionRepository,
            PlaidTokenEncryption tokenEncryption,
            TransactionTemplate transactionTemplate
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.tokenEncryption = tokenEncryption;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Pulls every page Plaid has for the item. Returns the number of transactions added,
     * modified or removed.
     */
    int sync(PlaidItem item) {
        String itemId = item.getItemId();
        log.info("Starting transactions sync for item {}", itemId);

        String accessToken = tokenEncryption.decrypt(
                item.getEncryptedAccessToken(), item.getUserId(), itemId);

        String cursor = item.getTransactionsCursor();
        int added = 0;
        int modified = 0;
        int removed = 0;
        int pages = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            TransactionsSyncResponse body = fetchPage(accessToken, cursor);
            PageCounts counts = applyPage(item, body);
            cursor = body.getNextCursor();
            added += counts.added();
            modified += counts.modified();
            removed += counts.removed();
            pages++;
            log.debug(
                    "Applied transactions sync page {} for item {}: {} added, {} modified, {} removed",
                    pages, itemId, counts.added(), counts.modified(), counts.removed());
            if (!Boolean.TRUE.equals(body.getHasMore())) {
                log.info(
                        "Finished transactions sync for item {}: {} added, {} modified, {} removed ({} pages)",
                        itemId, added, modified, removed, pages);
                return added + modified + removed;
            }
        }

        throw new IllegalStateException("Plaid transactions sync exceeded " + MAX_PAGES + " pages");
    }

    private TransactionsSyncResponse fetchPage(String accessToken, String cursor) {
        TransactionsSyncRequest request = new TransactionsSyncRequest()
                .accessToken(accessToken)
                .cursor(cursor)
                .count(PAGE_SIZE)
                .options(new TransactionsSyncRequestOptions().includePersonalFinanceCategory(true));

        return PlaidCalls.execute(plaidApi.transactionsSync(request), "transactions sync");
    }

    /**
     * Persists one page and its cursor together, so a later page failing cannot skip
     * transactions we never stored.
     */
    private PageCounts applyPage(PlaidItem item, TransactionsSyncResponse page) {
        List<Transaction> added = Objects.requireNonNullElse(page.getAdded(), List.of());
        List<Transaction> modified = Objects.requireNonNullElse(page.getModified(), List.of());
        List<RemovedTransaction> removed = Objects.requireNonNullElse(page.getRemoved(), List.of());

        transactionTemplate.executeWithoutResult(status -> {
            upsertAccounts(item, page.getAccounts());

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

            plaidItemRepository.updateTransactionsCursor(item.getItemId(), page.getNextCursor());
        });

        return new PageCounts(added.size(), modified.size(), removed.size());
    }

    private record PageCounts(int added, int modified, int removed) {
    }

    private void upsertAccounts(PlaidItem item, List<AccountBase> plaidAccounts) {
        if (plaidAccounts == null || plaidAccounts.isEmpty()) {
            return;
        }

        for (AccountBase plaidAccount : plaidAccounts) {
            PlaidAccount account = accountRepository.findById(plaidAccount.getAccountId())
                    .orElseGet(() -> new PlaidAccount(
                            plaidAccount.getAccountId(), item.getItemId(), item.getUserId()));
            AccountBalance balances = plaidAccount.getBalances();
            account.updateSnapshot(
                    Objects.requireNonNullElse(plaidAccount.getName(), "Account"),
                    plaidAccount.getOfficialName(),
                    plaidAccount.getMask(),
                    plaidAccount.getType() == null ? "other" : plaidAccount.getType().getValue(),
                    plaidAccount.getSubtype() == null ? null : plaidAccount.getSubtype().getValue(),
                    money(balances == null ? null : balances.getAvailable()),
                    money(balances == null ? null : balances.getCurrent()),
                    money(balances == null ? null : balances.getLimit()),
                    balances == null ? null : balances.getIsoCurrencyCode(),
                    balances == null ? null : balances.getUnofficialCurrencyCode()
            );
            accountRepository.save(account);
        }
    }

    private static BigDecimal money(Double amount) {
        return amount == null ? null : BigDecimal.valueOf(amount);
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
