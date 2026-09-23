import type { RecurringStream } from './PlaidService'

// Plaid's primary personal finance categories, minus the incoming ones the server leaves out
// of spending.
const CATEGORY_LABELS: Record<string, string> = {
  BANK_FEES: 'Bank fees',
  ENTERTAINMENT: 'Entertainment',
  FOOD_AND_DRINK: 'Food & drink',
  GENERAL_MERCHANDISE: 'Shopping',
  GENERAL_SERVICES: 'Services',
  GOVERNMENT_AND_NON_PROFIT: 'Government & charity',
  HOME_IMPROVEMENT: 'Home improvement',
  LOAN_PAYMENTS: 'Loan payments',
  MEDICAL: 'Medical',
  PERSONAL_CARE: 'Personal care',
  RENT_AND_UTILITIES: 'Rent & utilities',
  TRANSPORTATION: 'Transportation',
  TRANSFER_OUT: 'Transfers out',
  TRAVEL: 'Travel',
  UNCATEGORIZED: 'Uncategorized',
}

export function firstPresent(...values: Array<string | null | undefined>) {
  return values.find((value) => value != null && value.trim() !== '')
}

/** A recurring stream's display name, falling back to its category when Plaid sent a blank name. */
export function recurringLabel(stream: RecurringStream) {
  return (
    firstPresent(stream.merchant_name, stream.description) ??
    (stream.category ? categoryLabel(stream.category) : undefined) ??
    'Recurring'
  )
}

// Plaid can add primary categories, so fall back to a readable form of whatever it sends.
export function categoryLabel(category: string) {
  return (
    CATEGORY_LABELS[category] ??
    category
      .toLowerCase()
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ')
  )
}
