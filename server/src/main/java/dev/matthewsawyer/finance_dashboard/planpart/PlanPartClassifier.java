package dev.matthewsawyer.finance_dashboard.planpart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.PlanPart;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which plan part one transaction belongs to, using the user's plan lines to settle
 * cases that depend on the person, like whether a streaming subscription is a fixed cost.
 */
@Component
class PlanPartClassifier {

    // Option keys are what the model reads, so they're lower-case words rather than enum names.
    private static final Map<String, Object> CRITERIA = ordered(
            "fixed_costs", ordered(
                    "what", "Bills and necessities the user must pay to live: rent or mortgage, utilities, "
                            + "phone, internet, insurance, loan and car payments, groceries, commuting, "
                            + "basic clothing and committed subscriptions."),
            "guilt_free", ordered(
                    "what", "Optional spending the user chooses to enjoy: eating out, coffee, bars, "
                            + "entertainment, travel, hobbies, gifts, donations and non-essential shopping.",
                    "includes", "Cash withdrawals and payments sent to other people, since that money gets spent."),
            "savings", ordered(
                    "what", "Money moved into a savings account and set aside for a future goal, such as "
                            + "an emergency fund, a vacation or a house down payment.",
                    "not_for", "Purchases themselves, even ones a savings goal will pay for, like a hotel booking."),
            "investments", ordered(
                    "what", "Money moved into an investment or retirement account, such as a brokerage "
                            + "account, 401(k) or IRA."),
            "not_counted", ordered(
                    "what", "Money moving between the user's own everyday accounts, such as checking to "
                            + "checking, without being spent, saved or invested.",
                    "not_for", "Cash withdrawals or payments to other people."));

    private static final String QUESTION =
            "Which part of the user's Conscious Spending Plan does `transaction` belong to?";

    private static final Map<String, Object> WITH_PLAN = ordered(
            "type", "choice",
            "instructions", ordered(
                    "question", QUESTION,
                    "guidance", "When `transaction` matches a line in `spending_plan`, choose the part that line is in."),
            "criteria", CRITERIA);

    private static final Map<String, Object> WITHOUT_PLAN = ordered(
            "type", "choice",
            "instructions", QUESTION,
            "criteria", CRITERIA);

    private final TypeSafeClient typeSafe;

    PlanPartClassifier(TypeSafeClient typeSafe) {
        this.typeSafe = typeSafe;
    }

    boolean isAvailable() {
        return typeSafe.isConfigured();
    }

    PlanPart classify(PlaidTransaction transaction, PlanLines plan) {
        TypeSafeClient.ChoiceAnswer answer = plan.isEmpty()
                ? typeSafe.choose(new State(TransactionState.of(transaction), null), WITHOUT_PLAN)
                : typeSafe.choose(new State(TransactionState.of(transaction), PlanState.of(plan)), WITH_PLAN);
        return PlanPart.valueOf(answer.choice().toUpperCase(Locale.ROOT));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record State(
            @JsonProperty("transaction") TransactionState transaction,
            @JsonProperty("spending_plan") PlanState spendingPlan
    ) {
    }

    record TransactionState(
            @JsonProperty("description") String description,
            @JsonProperty("merchant") String merchant,
            @JsonProperty("amount_usd") BigDecimal amountUsd,
            @JsonProperty("direction") String direction,
            @JsonProperty("plaid_category") String plaidCategory,
            @JsonProperty("payment_channel") String paymentChannel
    ) {
        // The model reads amounts better without a sign, so direction says which way money moved.
        static TransactionState of(PlaidTransaction transaction) {
            BigDecimal amount = transaction.getAmount();
            return new TransactionState(
                    transaction.getName(),
                    transaction.getMerchantName(),
                    amount.abs().setScale(2, RoundingMode.HALF_UP),
                    amount.signum() < 0 ? "refund" : "money out",
                    readableCategory(
                            transaction.getPersonalFinanceCategoryPrimary(),
                            transaction.getPersonalFinanceCategoryDetailed()),
                    transaction.getPaymentChannel());
        }
    }

    record PlanState(
            @JsonProperty("fixed_costs") Lines fixedCosts,
            @JsonProperty("investments") Lines investments,
            @JsonProperty("savings") Lines savings
    ) {
        static PlanState of(PlanLines plan) {
            return new PlanState(
                    new Lines(plan.fixedCosts()), new Lines(plan.investments()), new Lines(plan.savings()));
        }
    }

    record Lines(@JsonProperty("lines") List<String> lines) {
    }

    /** Turns FOOD_AND_DRINK / FOOD_AND_DRINK_COFFEE into "food and drink: coffee". */
    static String readableCategory(String primary, String detailed) {
        if (detailed == null) {
            return primary == null ? null : words(primary);
        }
        if (primary == null || !detailed.startsWith(primary + "_")) {
            return words(detailed);
        }
        return words(primary) + ": " + words(detailed.substring(primary.length() + 1));
    }

    private static String words(String category) {
        return category.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
