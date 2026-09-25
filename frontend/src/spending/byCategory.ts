import type { CategorySpend, SpendingByBucket } from '../api/PlaidService'
import { roundCents } from '../spendingPlan/money'

/**
 * Categorical slots from the validated dataviz palette, in fixed order. The first four match
 * the plan colors, so the chart looks familiar before and after there's a plan.
 */
const CATEGORY_COLORS = [
  '#2a78d6',
  '#eb6834',
  '#1baf7a',
  '#eda100',
  '#e87ba4',
  '#008300',
  '#4a3aa7',
  '#e34948',
]
// More categories than slots share a neutral, rather than generating hues that blur together.
export const OTHER_CATEGORY_COLOR = '#9e9e9e'

export interface CategoryTotal extends CategorySpend {
  color: string
}

/**
 * Spending by Plaid category regardless of bucket, largest first, for before there's a plan to
 * sort transactions into buckets.
 */
export function spendingByCategory(summary: SpendingByBucket): CategoryTotal[] {
  const merged = new Map<string, CategorySpend>()
  for (const bucket of summary.buckets) {
    for (const entry of bucket.categories) {
      const existing = merged.get(entry.category)
      if (existing) {
        existing.amount = roundCents(existing.amount + entry.amount)
        existing.transactions.push(...entry.transactions)
      } else {
        merged.set(entry.category, { ...entry, transactions: [...entry.transactions] })
      }
    }
  }
  return [...merged.values()]
    .sort((a, b) => b.amount - a.amount)
    .map((entry, index) => ({
      ...entry,
      // Merging buckets interleaves dates, so restore newest first.
      transactions: entry.transactions.sort((a, b) => b.date.localeCompare(a.date)),
      color: CATEGORY_COLORS[index] ?? OTHER_CATEGORY_COLOR,
    }))
}
