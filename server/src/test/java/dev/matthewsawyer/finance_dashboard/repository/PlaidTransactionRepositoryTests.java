package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.PlanPart;
import dev.matthewsawyer.finance_dashboard.planpart.PlanPartSorting;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class PlaidTransactionRepositoryTests {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private EntityManager entityManager;

    private final UUID userId = UUID.randomUUID();

    @Test
    void findsSpendingButNotPayCardPaymentsOrMovesBetweenOwnAccounts() {
        store("rent", "3200", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT", PlanPart.FIXED_COSTS);
        store("coffee", "6", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE", null);
        store("no-category", "10", null, null, PlanPart.GUILT_FREE);
        store("to-checking", "500", "TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER", PlanPart.NOT_COUNTED);
        store("pay", "-4000", "INCOME", "INCOME_SALARY", null);
        store("card-payment", "900", "LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT", null);

        Set<String> found = transactions
                .findSpending(userId, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), null,
                        PlanPartSorting.NOT_PLAN_MONEY)
                .stream()
                .map(PlaidTransaction::getTransactionId)
                .collect(Collectors.toSet());

        assertEquals(Set.of("rent", "coffee", "no-category"), found);
    }

    private void store(String id, String amount, String primary, String detailed, PlanPart part) {
        transactions.saveAndFlush(new PlaidTransaction(id, "item", userId, "checking", new BigDecimal(amount), DAY)
                .personalFinanceCategory(primary, detailed));
        entityManager.clear();
        if (part != null) {
            PlaidTransaction stored = transactions.findById(id).orElseThrow();
            transactions.updatePlanPart(id, stored.getUpdatedAt(), part);
        }
    }
}
