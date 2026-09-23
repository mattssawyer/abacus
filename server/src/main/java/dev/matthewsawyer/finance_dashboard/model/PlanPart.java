package dev.matthewsawyer.finance_dashboard.model;

/**
 * Where a transaction's money went in the Conscious Spending Plan. Unlike {@link SpendingPlanBucket},
 * this includes guilt-free spending, plus money that only moved between the user's own accounts.
 */
public enum PlanPart {
    FIXED_COSTS,
    GUILT_FREE,
    SAVINGS,
    INVESTMENTS,
    NOT_COUNTED
}
