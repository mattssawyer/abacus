import { describe, expect, it } from 'vitest'
import {
  PLAN_TARGETS,
  defaultPlan,
  evaluatePlan,
  lineAmount,
  type PlanDraft,
  type PlanLineDraft,
} from './plan'

function line(overrides: Partial<PlanLineDraft>): PlanLineDraft {
  return { name: 'Line', amount: null, items: [], fromPaycheck: false, ...overrides }
}

function plan(buckets: Partial<PlanDraft>): PlanDraft {
  return { fixedCosts: [], investments: [], savings: [], ...buckets }
}

/** A plan with one typed amount per bucket and no buffer, for share and target checks. */
function simplePlan(fixedCosts: number, investments: number, savings: number) {
  return plan({
    fixedCosts: [line({ name: 'Rent', amount: fixedCosts })],
    investments: [line({ name: 'Roth IRA', amount: investments })],
    savings: [line({ name: 'Emergency fund', amount: savings })],
  })
}

describe('lineAmount', () => {
  it('uses the typed amount until the line is broken down', () => {
    expect(lineAmount(line({ name: 'Rent', amount: 1450 }))).toBe(1450)
    expect(lineAmount(line({ name: 'Rent', amount: null }))).toBeNull()
  })

  it('sums breakdown items, counting blank ones as zero', () => {
    expect(
      lineAmount(
        line({
          name: 'Subscriptions',
          amount: 99,
          items: [
            { name: 'Netflix', amount: 15.49, streamId: null },
            { name: 'Spotify', amount: 11.99, streamId: null },
            { name: '', amount: null, streamId: null },
          ],
        }),
      ),
    ).toBe(27.48)
  })
})

describe('bucket totals', () => {
  it('add typed amounts and broken-down lines, skipping blanks', () => {
    const investments = [
      line({ name: '401(k)', amount: 500 }),
      line({ name: 'Roth IRA', amount: null }),
      line({
        name: 'Other investments',
        items: [
          { name: 'Vanguard', amount: 100.1, streamId: null },
          { name: 'Fidelity', amount: 50.2, streamId: null },
        ],
      }),
    ]

    expect(evaluatePlan(null, plan({ investments })).buckets.investments.amount).toBe(650.3)
  })
})

describe('paycheck contributions', () => {
  it('starts with only the 401(k) marked as taken from the paycheck', () => {
    const draft = defaultPlan()
    expect(draft.investments.filter((row) => row.fromPaycheck).map((row) => row.name)).toEqual([
      '401(k)',
    ])
    expect([...draft.fixedCosts, ...draft.savings].some((row) => row.fromPaycheck)).toBe(false)
  })

  it('adds up only the lines taken from the paycheck', () => {
    const investments = [
      line({ name: '401(k)', amount: 500, fromPaycheck: true }),
      line({
        name: 'HSA',
        fromPaycheck: true,
        items: [{ name: 'Employer HSA', amount: 100, streamId: null }],
      }),
      line({ name: 'Roth IRA', amount: 583, fromPaycheck: false }),
    ]

    expect(evaluatePlan(5200, plan({ investments })).fromPaycheck).toBe(600)
  })

  it('adds paycheck contributions back onto take-home pay for plan income', () => {
    const investments = [line({ name: '401(k)', amount: 500, fromPaycheck: true })]

    expect(evaluatePlan(5200, plan({ investments })).income).toBe(5700)
    expect(evaluatePlan(5200, plan({})).income).toBe(5200)
    expect(evaluatePlan(null, plan({ investments })).income).toBeNull()
  })

  it('counts a paycheck 401(k) toward investments without shrinking guilt-free spending', () => {
    const evaluation = evaluatePlan(
      5200,
      plan({
        fixedCosts: [line({ name: 'Rent', amount: 2800 })],
        investments: [line({ name: '401(k)', amount: 500, fromPaycheck: true })],
        savings: [line({ name: 'Emergency fund', amount: 300 })],
      }),
      0,
    )

    expect(evaluation.guiltFree.amount).toBe(5200 - 2800 - 300)
    expect(evaluation.buckets.investments.share).toBe(9)
  })
})

describe('fixed-cost buffer', () => {
  it('is a percent of fixed costs, rounded to cents, and nothing when blank', () => {
    const fixedCosts = [line({ name: 'Rent', amount: 1477.48 })]

    expect(evaluatePlan(null, plan({ fixedCosts }), 15).buffer).toBe(221.62)
    expect(evaluatePlan(null, plan({ fixedCosts }), 0).buffer).toBe(0)
    expect(evaluatePlan(null, plan({ fixedCosts }), null).buffer).toBe(0)
  })

  it('counts toward fixed costs, and so comes out of guilt-free spending', () => {
    const draft = defaultPlan()
    draft.fixedCosts[0] = { ...draft.fixedCosts[0]!, amount: 2000 }

    const evaluation = evaluatePlan(5000, draft, 15)

    expect(evaluation.fixedCostSubtotal).toBe(2000)
    expect(evaluation.buffer).toBe(300)
    expect(evaluation.buckets.fixedCosts.amount).toBe(2300)
    expect(evaluation.guiltFree.amount).toBe(2700)
  })

  it('defaults to the spreadsheet 15 percent', () => {
    const draft = defaultPlan()
    draft.fixedCosts[0] = { ...draft.fixedCosts[0]!, amount: 1000 }

    expect(evaluatePlan(4000, draft).buffer).toBe(150)
  })
})

describe('guilt-free spending', () => {
  it('is what plan income leaves after the buckets', () => {
    expect(evaluatePlan(5000, simplePlan(2900.5, 500, 500), 0).guiltFree.amount).toBe(1099.5)
    expect(evaluatePlan(3000, simplePlan(2400, 500, 500), 0).guiltFree.amount).toBe(-400)
  })

  it('is unknown until take-home pay is entered', () => {
    const { guiltFree } = evaluatePlan(null, simplePlan(1200, 0, 0), 0)

    expect(guiltFree.amount).toBeNull()
    expect(guiltFree.share).toBeNull()
    expect(guiltFree.status).toBeNull()
    expect(guiltFree.flagged).toBe(false)
  })
})

describe('shares and targets', () => {
  it('measures each part as a rounded percent of plan income', () => {
    const evaluation = evaluatePlan(3600, simplePlan(1800, 360, 180), 0)

    expect(evaluation.buckets.fixedCosts.share).toBe(50)
    expect(evaluation.buckets.investments.share).toBe(10)
    expect(evaluation.buckets.savings.share).toBe(5)
    expect(evaluation.guiltFree.share).toBe(35)
  })

  it('has no share until there is plan income to measure against', () => {
    expect(evaluatePlan(null, simplePlan(1800, 0, 0), 0).buckets.fixedCosts.share).toBeNull()
    expect(evaluatePlan(0, simplePlan(1800, 0, 0), 0).buckets.fixedCosts.share).toBeNull()
  })

  it('places each share below, within or above its target', () => {
    const evaluation = evaluatePlan(1000, simplePlan(700, 100, 20), 0)

    expect(evaluation.buckets.fixedCosts.target).toEqual(PLAN_TARGETS.fixedCosts)
    expect(evaluation.buckets.fixedCosts.status).toBe('above')
    expect(evaluation.buckets.investments.status).toBe('within')
    expect(evaluation.buckets.savings.status).toBe('below')
    expect(evaluation.guiltFree.status).toBe('below')
  })
})

describe('flagged', () => {
  it('flags fixed costs above their target', () => {
    expect(evaluatePlan(1000, simplePlan(610, 100, 50), 0).buckets.fixedCosts.flagged).toBe(true)
    expect(evaluatePlan(1000, simplePlan(600, 100, 50), 0).buckets.fixedCosts.flagged).toBe(false)
  })

  it('flags guilt-free spending below zero', () => {
    expect(evaluatePlan(1000, simplePlan(900, 100, 50), 0).guiltFree.flagged).toBe(true)
    expect(evaluatePlan(1000, simplePlan(900, 100, 0), 0).guiltFree.flagged).toBe(false)
  })

  it('does not flag other parts outside their targets', () => {
    const evaluation = evaluatePlan(1000, simplePlan(300, 300, 0), 0)

    expect(evaluation.buckets.investments.status).toBe('above')
    expect(evaluation.buckets.investments.flagged).toBe(false)
    expect(evaluation.buckets.savings.status).toBe('below')
    expect(evaluation.buckets.savings.flagged).toBe(false)
    expect(evaluation.guiltFree.status).toBe('above')
    expect(evaluation.guiltFree.flagged).toBe(false)
  })
})
