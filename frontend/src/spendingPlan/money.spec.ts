import { describe, expect, it } from 'vitest'
import { formatPlanAmount, parseAmount, roundCents } from './money'

describe('parseAmount', () => {
  it('reads typed currency and treats a blank field as empty', () => {
    expect(parseAmount('1,800.50')).toBe(1800.5)
    expect(parseAmount('$80')).toBe(80)
    expect(parseAmount('')).toBeNull()
  })
})

describe('formatPlanAmount', () => {
  it('shows cents only when there are some', () => {
    expect(formatPlanAmount(1450)).toBe('$1,450')
    expect(formatPlanAmount(15.49)).toBe('$15.49')
    expect(formatPlanAmount(-400)).toBe('-$400')
  })
})

describe('roundCents', () => {
  it('rounds to the nearest cent', () => {
    expect(roundCents(221.622)).toBe(221.62)
    expect(roundCents(0.1 + 0.2)).toBe(0.3)
  })
})
