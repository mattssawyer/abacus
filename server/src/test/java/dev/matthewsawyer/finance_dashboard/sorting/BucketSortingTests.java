package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class BucketSortingTests {

    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private SpendingPlanService planService;
    @Autowired private EntityManager entityManager;

    private final TypeSafeClient typeSafe = mock(TypeSafeClient.class);
    private BucketSorting sorting;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // Jobs and questions run inline so each test sees the finished sort.
        sorting = new BucketSorting(
                transactions, planService, new BucketClassifier(typeSafe), Runnable::run, Runnable::run);
        userId = UUID.randomUUID();
        when(typeSafe.isConfigured()).thenReturn(true);
    }

    @Test
    void storesTheBucketTypeSafeChooses() {
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        answer(Map.of("Rent ACH", "fixed_costs", "Starbucks", "guilt_free"));

        sorting.sortLater(userId);

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
        assertEquals(Bucket.GUILT_FREE, bucket("coffee"));
    }

    @Test
    void neverAsksAboutPayOrCardPayments() {
        store(new PlaidTransaction("pay", "item", userId, "checking", new BigDecimal("-4000"), LocalDate.now())
                .name("ACME payroll")
                .personalFinanceCategory("INCOME", "INCOME_SALARY"));
        store(transaction("card", "Card autopay", "LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"));

        sorting.sortLater(userId);

        verify(typeSafe, never()).choose(any(), any());
        assertNull(bucket("pay"));
        assertNull(bucket("card"));
    }

    @Test
    void givesTypeSafeThePlanLinesOnceAPlanIsSaved() {
        savePlan("Subscriptions");
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));

        sorting.sortLater(userId);

        BucketClassifier.State state = askedState();
        assertEquals(List.of("Subscriptions"), state.spendingPlan().fixedCosts().lines());
        assertEquals("entertainment: tv and movies", state.transaction().plaidCategory());
        assertEquals(new BigDecimal("15.49"), state.transaction().amountUsd());
        assertEquals("money out", state.transaction().direction());
    }

    @Test
    void asksWithoutPlanContextBeforeAPlanIsSaved() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"));

        sorting.sortLater(userId);

        assertNull(askedState().spendingPlan());
        assertEquals(Bucket.GUILT_FREE, bucket("netflix"));
    }

    @Test
    void leavesATransactionUnsortedWhenTypeSafeFails() {
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        when(typeSafe.choose(any(), any())).thenAnswer(invocation -> {
            if (description(invocation.getArgument(0)).equals("Starbucks")) {
                throw new IllegalStateException("TypeSafe unavailable");
            }
            return new TypeSafeClient.ChoiceAnswer("fixed_costs", 1.0);
        });

        sorting.sortLater(userId);

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
        assertNull(bucket("coffee"));
    }

    @Test
    void skipsSortingWithoutAnApiKey() {
        when(typeSafe.isConfigured()).thenReturn(false);
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));

        sorting.sortLater(userId);

        verify(typeSafe, never()).choose(any(), any());
        assertNull(bucket("rent"));
    }

    @Test
    void dropsAnAnswerWhenSyncChangedTheTransactionWhileItWasAsked() {
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        when(typeSafe.choose(any(), any())).thenAnswer(invocation -> {
            // Plaid sends a new copy of the transaction mid-sort. A bulk update stands in for sync
            // so the copy sorting already read stays as it was, like it would outside this test.
            entityManager.createQuery("""
                    UPDATE PlaidTransaction t SET t.name = 'Starbucks Reserve', t.updatedAt = :now
                    WHERE t.transactionId = 'coffee'
                    """)
                    .setParameter("now", Instant.now().plusSeconds(1))
                    .executeUpdate();
            return new TypeSafeClient.ChoiceAnswer("guilt_free", 1.0);
        });

        sorting.sortLater(userId);

        assertNull(bucket("coffee"));
    }

    @Test
    void sortsEverythingAgainWhenAPlanSaveChangesTheLines() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"));
        sorting.sortLater(userId);

        savePlan("Subscriptions");
        answer(Map.of("Netflix", "fixed_costs"));
        sorting.planSaved(userId, PlanLines.NONE);

        assertEquals(Bucket.FIXED_COSTS, bucket("netflix"));
    }

    @Test
    void leavesSortedTransactionsAloneWhenAPlanSaveKeepsTheLines() {
        savePlan("Subscriptions");
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));
        sorting.sortLater(userId);

        sorting.planSaved(userId, new PlanLines(List.of("Subscriptions"), List.of(), List.of()));

        verify(typeSafe, times(1)).choose(any(), any());
    }

    @Test
    void readsPlaidCategoriesAsWords() {
        assertEquals("food and drink: coffee",
                BucketClassifier.readableCategory("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        assertEquals("travel", BucketClassifier.readableCategory("TRAVEL", null));
        assertEquals("other transfer", BucketClassifier.readableCategory("TRANSFER_OUT", "OTHER_TRANSFER"));
        assertNull(BucketClassifier.readableCategory(null, null));
    }

    private PlaidTransaction transaction(String id, String name, String primary, String detailed) {
        return new PlaidTransaction(id, "item", userId, "checking", new BigDecimal("15.4900"), LocalDate.now())
                .name(name)
                .personalFinanceCategory(primary, detailed);
    }

    /** Saves and detaches, so sorting reads the transaction back from the database like it would in a job. */
    private void store(PlaidTransaction transaction) {
        transactions.saveAndFlush(transaction);
        entityManager.clear();
    }

    private void savePlan(String fixedCostLine) {
        planService.save(userId, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, fixedCostLine, null, false, List.of())),
                plan -> plan);
        entityManager.clear();
    }

    private void answer(Map<String, String> bucketByDescription) {
        doAnswer(invocation -> new TypeSafeClient.ChoiceAnswer(
                bucketByDescription.get(description(invocation.getArgument(0))), 1.0))
                .when(typeSafe).choose(any(), any());
    }

    private BucketClassifier.State askedState() {
        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(typeSafe).choose(state.capture(), any());
        return assertInstanceOf(BucketClassifier.State.class, state.getValue());
    }

    private static String description(Object state) {
        return ((BucketClassifier.State) state).transaction().description();
    }

    private Bucket bucket(String transactionId) {
        entityManager.clear();
        return transactions.findById(transactionId).orElseThrow().getBucket();
    }
}
