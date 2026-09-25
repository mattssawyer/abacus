package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetRequestOptions;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Replaces an item's stored recurring streams with what Plaid currently detects. */
@Component
class RecurringStreamsSync {

    private static final Logger log = LoggerFactory.getLogger(RecurringStreamsSync.class);

    private static final Set<String> EXCLUDED_CATEGORIES =
            Set.of("INCOME_INTEREST_EARNED", "INCOME_DIVIDENDS");

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidRecurringStreamRepository streamRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final TransactionTemplate transactionTemplate;

    RecurringStreamsSync(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidRecurringStreamRepository streamRepository,
            PlaidTokenEncryption tokenEncryption,
            TransactionTemplate transactionTemplate
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.streamRepository = streamRepository;
        this.tokenEncryption = tokenEncryption;
        this.transactionTemplate = transactionTemplate;
    }

    /** Returns how many streams were stored; none when Plaid hasn't detected any yet. */
    int sync(PlaidItem item) {
        String itemId = item.getItemId();
        log.info("Starting recurring stream sync for item {}", itemId);

        TransactionsRecurringGetRequest request = new TransactionsRecurringGetRequest()
                .accessToken(tokenEncryption.decrypt(
                        item.getEncryptedAccessToken(), item.getUserId(), itemId))
                .options(new TransactionsRecurringGetRequestOptions()
                        .includePersonalFinanceCategory(true));

        TransactionsRecurringGetResponse body = PlaidCalls.execute(
                plaidApi.transactionsRecurringGet(request), "recurring transactions get");
        List<PlaidRecurringStream> streams = new ArrayList<>();
        streams.addAll(mapStreams(body.getOutflowStreams(), item, false));
        streams.addAll(mapStreams(body.getInflowStreams(), item, true));

        transactionTemplate.executeWithoutResult(status -> {
            streamRepository.deleteAllByItemId(itemId);
            if (!streams.isEmpty()) {
                streamRepository.saveAll(streams);
            }
            plaidItemRepository.markRecurringSynced(itemId, Instant.now());
        });

        log.info("Stored {} recurring streams for item {}", streams.size(), itemId);
        return streams.size();
    }

    private static List<PlaidRecurringStream> mapStreams(
            List<TransactionStream> streams,
            PlaidItem item,
            boolean isInflow
    ) {
        return Objects.requireNonNullElse(streams, List.<TransactionStream>of()).stream()
                .filter(RecurringStreamsSync::isRecurringPayment)
                .map(stream -> toEntity(stream, item, isInflow))
                .filter(stream -> stream != null)
                .toList();
    }

    private static PlaidRecurringStream toEntity(
            TransactionStream stream,
            PlaidItem item,
            boolean isInflow
    ) {
        TransactionStreamAmount last = stream.getLastAmount();
        TransactionStreamAmount average = stream.getAverageAmount();
        Double amount = last != null && last.getAmount() != null
                ? last.getAmount()
                : average == null ? null : average.getAmount();
        if (amount == null) {
            return null;
        }
        String currency = last != null && last.getIsoCurrencyCode() != null
                ? last.getIsoCurrencyCode()
                : average == null ? null : average.getIsoCurrencyCode();
        RecurringTransactionFrequency frequency = stream.getFrequency();
        String frequencyValue = frequency == null
                || frequency == RecurringTransactionFrequency.ENUM_UNKNOWN
                ? RecurringTransactionFrequency.UNKNOWN.getValue()
                : frequency.getValue();
        PersonalFinanceCategory personalFinanceCategory = stream.getPersonalFinanceCategory();
        String category = personalFinanceCategory == null ? null : personalFinanceCategory.getPrimary();
        String categoryDetailed = personalFinanceCategory == null ? null : personalFinanceCategory.getDetailed();

        return new PlaidRecurringStream(
                stream.getStreamId(),
                item.getItemId(),
                item.getUserId(),
                stream.getAccountId(),
                BigDecimal.valueOf(amount),
                frequencyValue,
                isInflow
        )
                .merchantName(stream.getMerchantName())
                .description(stream.getDescription())
                .isoCurrencyCode(currency)
                .nextDate(stream.getPredictedNextDate())
                .lastDate(stream.getLastDate())
                .category(category)
                .categoryDetailed(categoryDetailed);
    }

    /** Deletes the recurring streams of an item the user removed. */
    void forget(String itemId) {
        transactionTemplate.executeWithoutResult(status -> streamRepository.deleteAllByItemId(itemId));
    }

    /**
     * Recurring is for named bills and paychecks. Bank interest and other unlabeled credits
     * are technically streams, but they are not useful on the home page or spending plan.
     */
    static boolean isRecurringPayment(TransactionStream stream) {
        if (Boolean.FALSE.equals(stream.getIsActive())) {
            return false;
        }
        if (isBlank(stream.getMerchantName()) && isBlank(stream.getDescription())) {
            return false;
        }
        String detailed = stream.getPersonalFinanceCategory() == null
                ? null
                : stream.getPersonalFinanceCategory().getDetailed();
        if (detailed != null && EXCLUDED_CATEGORIES.contains(detailed)) {
            return false;
        }
        String primary = stream.getPersonalFinanceCategory() == null
                ? null
                : stream.getPersonalFinanceCategory().getPrimary();
        if ("INCOME".equals(primary) && isBlank(stream.getMerchantName())) {
            return false;
        }
        return !looksLikeInterest(stream.getMerchantName()) && !looksLikeInterest(stream.getDescription());
    }

    private static boolean looksLikeInterest(String value) {
        return value != null && value.toLowerCase().contains("interest");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
