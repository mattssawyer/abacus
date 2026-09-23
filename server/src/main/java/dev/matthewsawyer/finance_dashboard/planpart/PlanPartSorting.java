package dev.matthewsawyer.finance_dashboard.planpart;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.PlanPart;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps each transaction's plan part current. Sorting asks TypeSafe about every transaction, so
 * it runs off the caller's thread, and its jobs run one at a time so an older job can never
 * overwrite a newer one's answers.
 *
 * <p>Failures leave transactions unsorted rather than guessing; the next sort retries them.
 */
@Service
public class PlanPartSorting {

    private static final Logger log = LoggerFactory.getLogger(PlanPartSorting.class);

    /**
     * Plaid categories that are never plan money: pay and other money coming in, and card
     * payments, whose purchases already count on the card itself. Matched against both the
     * primary and the detailed category.
     */
    public static final Set<String> NOT_PLAN_MONEY =
            Set.of("INCOME", "TRANSFER_IN", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT");

    private final PlaidTransactionRepository transactionRepository;
    private final SpendingPlanService planService;
    private final PlanPartClassifier classifier;
    private final Executor jobExecutor;
    private final Executor classifyExecutor;

    PlanPartSorting(
            PlaidTransactionRepository transactionRepository,
            SpendingPlanService planService,
            PlanPartClassifier classifier,
            @Qualifier("planPartJobExecutor") Executor jobExecutor,
            @Qualifier("planPartClassifyExecutor") Executor classifyExecutor
    ) {
        this.transactionRepository = transactionRepository;
        this.planService = planService;
        this.classifier = classifier;
        this.jobExecutor = jobExecutor;
        this.classifyExecutor = classifyExecutor;
    }

    /** Queues sorting of the user's transactions that don't have a plan part yet. */
    public void sortLater(UUID userId) {
        queue(userId, () -> run(userId, false));
    }

    /**
     * Re-sorts all of the user's transactions if the save changed their plan lines, since the
     * lines decide cases like whether a subscription is a fixed cost. Other plan changes, such as
     * take-home pay, don't affect sorting.
     */
    public void planSaved(UUID userId, PlanLines linesBefore) {
        queue(userId, () -> {
            if (!planLines(userId).equals(linesBefore)) {
                run(userId, true);
            }
        });
    }

    // A full queue shouldn't fail the sync or save that asked; the next sort catches up.
    private void queue(UUID userId, Runnable job) {
        try {
            jobExecutor.execute(job);
        } catch (RejectedExecutionException e) {
            log.warn("Plan part sorting queue is full; skipping a sort for user {}", userId);
        }
    }

    private void run(UUID userId, boolean includeSorted) {
        try {
            sort(userId, includeSorted);
        } catch (RuntimeException e) {
            log.warn("Plan part sorting failed for user {}", userId, e);
        }
    }

    private void sort(UUID userId, boolean includeSorted) {
        if (!classifier.isAvailable()) {
            log.info("Skipping plan part sorting for user {}: no TypeSafe API key is set", userId);
            return;
        }
        List<PlaidTransaction> toSort =
                transactionRepository.findToSort(userId, includeSorted, NOT_PLAN_MONEY);
        if (toSort.isEmpty()) {
            return;
        }

        PlanLines plan = planLines(userId);
        List<CompletableFuture<PlanPart>> answers = toSort.stream()
                .map(transaction -> CompletableFuture.supplyAsync(
                        () -> classifier.classify(transaction, plan), classifyExecutor))
                .toList();

        int changed = 0;
        int failed = 0;
        RuntimeException firstFailure = null;
        for (int i = 0; i < toSort.size(); i++) {
            PlaidTransaction transaction = toSort.get(i);
            PlanPart part;
            try {
                part = answers.get(i).join();
            } catch (CompletionException e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                continue;
            }
            if (part != transaction.getPlanPart()) {
                changed += transactionRepository.updatePlanPart(
                        transaction.getTransactionId(), transaction.getUpdatedAt(), part);
            }
        }

        log.info("Sorted {} transactions into plan parts for user {}: {} changed, {} failed",
                toSort.size(), userId, changed, failed);
        if (firstFailure != null) {
            log.warn("First plan part sorting failure for user {}", userId, firstFailure);
        }
    }

    private PlanLines planLines(UUID userId) {
        return planService.find(userId, PlanLines::of).orElse(PlanLines.NONE);
    }
}
