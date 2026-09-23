package dev.matthewsawyer.finance_dashboard.model;

/**
 * The Conscious Spending Plan bucket a transaction's money went to. Unlike
 * {@link SpendingPlanBucket}, this includes guilt-free spending, plus NOT_COUNTED for money that
 * only moved between the user's own accounts.
 */
public enum Bucket {
    FIXED_COSTS,
    GUILT_FREE,
    SAVINGS,
    INVESTMENTS,
    NOT_COUNTED
}
