import type { RecurringStream } from '../api/PlaidService'
import { recurringLabel } from '../api/plaidLabels'

export type BucketId = 'fixedCosts' | 'investments' | 'savings'

/** Default lines for each bucket, from the Conscious Spending Plan spreadsheet. */
export const PLAN_LINES = {
  fixedCosts: [
    'Rent/mortgage',
    'Utilities',
    'Insurance',
    'Car payment',
    'Debt payments',
    'Groceries',
    'Clothes',
    'Phone',
    'Internet',
    'Subscriptions',
  ],
  investments: ['401(k)', 'Roth IRA', 'Other investments'],
  savings: ['Emergency fund', 'Vacations', 'Gifts', 'House down payment'],
} as const satisfies Record<BucketId, readonly string[]>

type PlanLine = { [B in BucketId]: { bucket: B; line: (typeof PLAN_LINES)[B][number] } }[BucketId]

/**
 * The spreadsheet adds this share of fixed costs on top, for costs you forgot and prices that
 * rise. Adjustable per plan.
 */
export const DEFAULT_BUFFER_PERCENT = 15

/** Lines that start out marked as taken from the paycheck. */
const PAYCHECK_LINES: ReadonlySet<string> = new Set(['401(k)'])

/** Suggested share of take-home pay for each bucket, in percent. */
export const PLAN_TARGETS = {
  fixedCosts: { min: 50, max: 60 },
  investments: { min: 10, max: 10 },
  savings: { min: 5, max: 10 },
  guiltFree: { min: 20, max: 35 },
} as const

/**
 * Plaid detailed categories that count toward each line. Credit card payments are left out:
 * the purchases behind them already land in other buckets.
 */
const LINE_BY_DETAILED_CATEGORY: Record<string, PlanLine> = {
  RENT_AND_UTILITIES_RENT: { bucket: 'fixedCosts', line: 'Rent/mortgage' },
  LOAN_PAYMENTS_MORTGAGE_PAYMENT: { bucket: 'fixedCosts', line: 'Rent/mortgage' },
  RENT_AND_UTILITIES_GAS_AND_ELECTRICITY: { bucket: 'fixedCosts', line: 'Utilities' },
  RENT_AND_UTILITIES_WATER: { bucket: 'fixedCosts', line: 'Utilities' },
  RENT_AND_UTILITIES_SEWAGE_AND_WASTE_MANAGEMENT: { bucket: 'fixedCosts', line: 'Utilities' },
  RENT_AND_UTILITIES_OTHER_UTILITIES: { bucket: 'fixedCosts', line: 'Utilities' },
  GENERAL_SERVICES_INSURANCE: { bucket: 'fixedCosts', line: 'Insurance' },
  LOAN_PAYMENTS_CAR_PAYMENT: { bucket: 'fixedCosts', line: 'Car payment' },
  LOAN_PAYMENTS_STUDENT_LOAN_PAYMENT: { bucket: 'fixedCosts', line: 'Debt payments' },
  LOAN_PAYMENTS_PERSONAL_LOAN_PAYMENT: { bucket: 'fixedCosts', line: 'Debt payments' },
  LOAN_PAYMENTS_OTHER_PAYMENT: { bucket: 'fixedCosts', line: 'Debt payments' },
  FOOD_AND_DRINK_GROCERIES: { bucket: 'fixedCosts', line: 'Groceries' },
  GENERAL_MERCHANDISE_CLOTHING_AND_ACCESSORIES: { bucket: 'fixedCosts', line: 'Clothes' },
  RENT_AND_UTILITIES_TELEPHONE: { bucket: 'fixedCosts', line: 'Phone' },
  RENT_AND_UTILITIES_INTERNET_AND_CABLE: { bucket: 'fixedCosts', line: 'Internet' },
  ENTERTAINMENT_TV_AND_MOVIES: { bucket: 'fixedCosts', line: 'Subscriptions' },
  ENTERTAINMENT_MUSIC_AND_AUDIO: { bucket: 'fixedCosts', line: 'Subscriptions' },
  PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS: { bucket: 'fixedCosts', line: 'Subscriptions' },
  // Plaid can't tell a Roth IRA from a brokerage account, so transfers go to the catch-all line.
  TRANSFER_OUT_INVESTMENT_AND_RETIREMENT_FUNDS: {
    bucket: 'investments',
    line: 'Other investments',
  },
  TRANSFER_OUT_SAVINGS: { bucket: 'savings', line: 'Emergency fund' },
}

const MONTHLY_MULTIPLIER: Record<string, number> = {
  WEEKLY: 52 / 12,
  BIWEEKLY: 26 / 12,
  SEMI_MONTHLY: 2,
  MONTHLY: 1,
  ANNUALLY: 1 / 12,
}

export interface PlanItemDraft {
  name: string
  amount: number | null
  /**
   * The recurring stream this item was filled from, or null if typed in. A saved plan keeps its
   * own amounts; this link is what lets later changes to the stream be offered as suggestions.
   */
  streamId: string | null
}

/**
 * A line in one of the plan's buckets. With no breakdown items, `amount` is what the user typed;
 * with items, the line's amount is their sum and `amount` is unused.
 */
export interface PlanLineDraft {
  name: string
  amount: number | null
  items: PlanItemDraft[]
  /**
   * Taken out of the paycheck before it's deposited, like most 401(k)s. Take-home pay already
   * excludes it, so it's added back to the income base instead of being subtracted twice.
   */
  fromPaycheck: boolean
}

export function toMonthlyAmount(amount: number, frequency: string): number {
  const multiplier = MONTHLY_MULTIPLIER[frequency] ?? 1
  return roundCents(Math.abs(amount) * multiplier)
}

export function estimateMonthlyTakeHome(streams: RecurringStream[]): number | null {
  const total = streams
    .filter((stream) => stream.is_inflow)
    .reduce((sum, stream) => sum + toMonthlyAmount(stream.amount, stream.frequency), 0)

  return total > 0 ? roundCents(total) : null
}

export type PlanDraft = Record<BucketId, PlanLineDraft[]>

export function defaultPlan(): PlanDraft {
  return planFromRecurring([])
}

/** The spreadsheet's lines for every bucket, broken down into the recurring bills that match. */
export function planFromRecurring(streams: RecurringStream[]): PlanDraft {
  const itemsByLine = new Map<string, PlanItemDraft[]>()
  for (const stream of streams) {
    if (stream.is_inflow || !stream.category_detailed) continue
    const match = LINE_BY_DETAILED_CATEGORY[stream.category_detailed]
    if (!match) continue
    const key = lineKey(match.bucket, match.line)
    const items = itemsByLine.get(key) ?? []
    items.push({
      name: recurringLabel(stream),
      amount: toMonthlyAmount(stream.amount, stream.frequency),
      streamId: stream.stream_id,
    })
    itemsByLine.set(key, items)
  }

  const linesFor = (bucket: BucketId): PlanLineDraft[] =>
    PLAN_LINES[bucket].map((name) => ({
      name,
      amount: null,
      items: itemsByLine.get(lineKey(bucket, name)) ?? [],
      fromPaycheck: bucket === 'investments' && PAYCHECK_LINES.has(name),
    }))

  return {
    fixedCosts: linesFor('fixedCosts'),
    investments: linesFor('investments'),
    savings: linesFor('savings'),
  }
}

export function lineAmount(line: PlanLineDraft): number | null {
  if (line.items.length === 0) return line.amount
  return roundCents(line.items.reduce((sum, item) => sum + (item.amount ?? 0), 0))
}

export function bucketTotal(lines: PlanLineDraft[]): number {
  return roundCents(lines.reduce((sum, line) => sum + (lineAmount(line) ?? 0), 0))
}

/** Contributions taken from the paycheck, which take-home pay has already had removed. */
export function paycheckContributions(lines: PlanLineDraft[]): number {
  return bucketTotal(lines.filter((line) => line.fromPaycheck))
}

/**
 * The income every bucket is measured against: take-home pay plus paycheck contributions, so a
 * 401(k) counts toward investments without being subtracted from take-home pay a second time.
 */
export function planIncome(takeHome: number | null, fromPaycheck: number): number | null {
  if (takeHome == null) return null
  return roundCents(takeHome + fromPaycheck)
}

/** What's left of plan income after the other buckets; negative when they add up to more. */
export function guiltFreeAmount(income: number | null, plannedTotal: number): number | null {
  if (income == null) return null
  return roundCents(income - plannedTotal)
}

export interface PlanSummary {
  /** Bucket totals; fixed costs include the buffer. */
  totals: Record<BucketId, number>
  /** Fixed cost lines before the buffer. */
  fixedCostSubtotal: number
  buffer: number
  /** Investments taken from the paycheck, added back onto take-home pay. */
  fromPaycheck: number
  /** Take-home pay plus paycheck contributions; what every share is measured against. */
  income: number | null
  guiltFree: number | null
}

export function summarizePlan(
  takeHome: number | null,
  plan: PlanDraft,
  bufferPercent: number | null = DEFAULT_BUFFER_PERCENT,
): PlanSummary {
  const fixedCostSubtotal = bucketTotal(plan.fixedCosts)
  const buffer = bufferAmount(fixedCostSubtotal, bufferPercent)
  const totals = {
    fixedCosts: roundCents(fixedCostSubtotal + buffer),
    investments: bucketTotal(plan.investments),
    savings: bucketTotal(plan.savings),
  }
  const fromPaycheck = paycheckContributions(plan.investments)
  const income = planIncome(takeHome, fromPaycheck)
  const guiltFree = guiltFreeAmount(income, totals.fixedCosts + totals.investments + totals.savings)
  return { totals, fixedCostSubtotal, buffer, fromPaycheck, income, guiltFree }
}

/** A blank percent means no buffer. */
export function bufferAmount(fixedCostSubtotal: number, percent: number | null): number {
  return roundCents((fixedCostSubtotal * (percent ?? 0)) / 100)
}

export function parseAmount(value: string): number | null {
  const cleaned = value.replace(/[^0-9.]/g, '')
  if (!cleaned) return null
  const parsed = Number.parseFloat(cleaned)
  return Number.isFinite(parsed) ? parsed : null
}

export function formatPlanAmount(amount: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: amount % 1 === 0 ? 0 : 2,
  }).format(amount)
}

export function shareOfIncome(total: number, income: number | null): number | null {
  if (income == null || income <= 0) return null
  return Math.round((total / income) * 100)
}

function lineKey(bucket: BucketId, line: string) {
  return `${bucket}:${line}`
}

function roundCents(value: number): number {
  return Math.round(value * 100) / 100
}
