import type { BalancePoint } from '../api/InvestmentsService'

export const RANGES = ['1M', '3M', 'YTD', '1Y', 'All'] as const
export type Range = (typeof RANGES)[number]

function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** The first day a range covers, as YYYY-MM-DD, or undefined for all of history. */
export function rangeStart(range: Range, today: Date): string | undefined {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  switch (range) {
    case '1M':
      start.setMonth(start.getMonth() - 1)
      break
    case '3M':
      start.setMonth(start.getMonth() - 3)
      break
    case 'YTD':
      start.setMonth(0, 1)
      break
    case '1Y':
      start.setFullYear(start.getFullYear() - 1)
      break
    case 'All':
      return undefined
  }
  return isoDate(start)
}

export interface Change {
  amount: number
  /** Null when the range opened at zero, where a percentage means nothing. */
  percent: number | null
}

/** How much the value moved from the range's first day to its last, or null with under two days. */
export function changeOver(points: BalancePoint[]): Change | null {
  if (points.length < 2) return null
  const first = points[0]!.value
  const last = points[points.length - 1]!.value
  const amount = last - first
  return { amount, percent: first === 0 ? null : (amount / Math.abs(first)) * 100 }
}

export function latestValue(points: BalancePoint[]): number | null {
  return points.length ? points[points.length - 1]!.value : null
}

const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const signedMoney = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  signDisplay: 'exceptZero',
})
const compactMoney = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  notation: 'compact',
  maximumFractionDigits: 1,
})

export function formatMoney(value: number): string {
  return money.format(value)
}

export function formatCompactMoney(value: number): string {
  return compactMoney.format(value)
}

export function formatChange(change: Change): string {
  const amount = signedMoney.format(change.amount)
  if (change.percent == null) return amount
  const percent = new Intl.NumberFormat('en-US', {
    maximumFractionDigits: 1,
    signDisplay: 'exceptZero',
  }).format(change.percent)
  return `${amount} (${percent}%)`
}

/** A day as "Sep 7", or "Sep 7, 2025" when the year matters. */
export function formatDay(date: string, withYear = false): string {
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    ...(withYear ? { year: 'numeric' } : {}),
  }).format(new Date(`${date}T00:00:00`))
}
