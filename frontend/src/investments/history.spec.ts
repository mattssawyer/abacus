import { describe, expect, it } from 'vitest'
import { changeOver, formatChange, rangeStart } from './history'

const point = (date: string, value: number) => ({ date, value })

describe('range start', () => {
  const today = new Date(2026, 8, 24)

  it('counts months and years back from today', () => {
    expect(rangeStart('1M', today)).toBe('2026-08-24')
    expect(rangeStart('3M', today)).toBe('2026-06-24')
    expect(rangeStart('1Y', today)).toBe('2025-09-24')
  })

  it('starts the year to date on January 1', () => {
    expect(rangeStart('YTD', today)).toBe('2026-01-01')
  })

  it('leaves all of history open-ended', () => {
    expect(rangeStart('All', today)).toBeUndefined()
  })
})

describe('change over a range', () => {
  it('compares the last day with the first', () => {
    expect(
      changeOver([point('2026-09-01', 1000), point('2026-09-02', 900), point('2026-09-03', 1100)]),
    ).toEqual({ amount: 100, percent: 10 })
  })

  it('needs at least two days', () => {
    expect(changeOver([point('2026-09-01', 1000)])).toBeNull()
  })

  it('measures a negative starting net worth by its size', () => {
    expect(changeOver([point('2026-09-01', -2000), point('2026-09-02', -1000)])).toEqual({
      amount: 1000,
      percent: 50,
    })
  })

  it('has no percentage from zero', () => {
    const change = changeOver([point('2026-09-01', 0), point('2026-09-02', 500)])!
    expect(change.percent).toBeNull()
    expect(formatChange(change)).toBe('+$500.00')
  })

  it('shows the amount and percentage with signs', () => {
    expect(formatChange({ amount: -250.5, percent: -2.345 })).toBe('-$250.50 (-2.3%)')
  })
})
