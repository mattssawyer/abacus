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

export function roundCents(value: number): number {
  return Math.round(value * 100) / 100
}
