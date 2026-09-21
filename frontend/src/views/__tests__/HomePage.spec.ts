import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed } from 'vue'
import PrimeVue from 'primevue/config'
import HomePage from '../HomePage.vue'
import {
  createLinkToken,
  exchangePublicToken,
  getAccounts,
  getLinkedItemIds,
  getSpendingByCategory,
  getTransactions,
  type PlaidAccount,
  type PlaidTransaction,
  type SpendingByCategory,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  createLinkToken: vi.fn(),
  exchangePublicToken: vi.fn(),
  getAccounts: vi.fn(),
  getLinkedItemIds: vi.fn(),
  getSpendingByCategory: vi.fn(),
  getTransactions: vi.fn(),
}))
const clerk = vi.hoisted(() => ({
  user: null as {
    firstName?: string | null
    primaryEmailAddress?: { emailAddress: string } | null
  } | null,
}))
vi.mock('@clerk/vue', () => ({
  UserButton: { template: '<div />' },
  useUser: () => ({ user: computed(() => clerk.user) }),
}))
enableAutoUnmount(afterEach)

const checking: PlaidAccount = {
  account_id: 'checking',
  balances: { current: 1250.5, available: 1200, iso_currency_code: null, limit: null },
  mask: '1234',
  name: 'Checking',
  official_name: null,
  subtype: 'checking',
  type: 'depository',
}

const savings: PlaidAccount = {
  account_id: 'savings',
  balances: { current: 8400, available: 8400, iso_currency_code: null, limit: null },
  mask: '5678',
  name: 'Savings',
  official_name: null,
  subtype: 'savings',
  type: 'depository',
}

const coffee: PlaidTransaction = {
  transaction_id: 'txn-1',
  account_id: 'checking',
  amount: 4.75,
  iso_currency_code: 'USD',
  date: '2026-09-17',
  name: 'COFFEE SHOP',
  merchant_name: 'Coffee Shop',
  logo_url: null,
  pending: false,
  category: 'FOOD_AND_DRINK',
}

const spending: SpendingByCategory = {
  start: '2026-09-01',
  end: '2026-09-30',
  total: 1544.5,
  categories: [
    { category: 'RENT_AND_UTILITIES', amount: 1450 },
    { category: 'FOOD_AND_DRINK', amount: 82.5 },
    { category: 'UNCATEGORIZED', amount: 12 },
  ],
}

let linkOptions: Parameters<Window['Plaid']['create']>[0]

function mountHome() {
  return mount(HomePage, {
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        AppSidebar: true,
        // Chart.js needs a real canvas, so assert on the data the chart is handed instead.
        Chart: {
          props: ['data'],
          template: `<ul class="chart-stub">
            <li v-for="(label, index) in data.labels" :key="label">
              {{ label }}: {{ data.datasets[0].data[index] }}
            </li>
          </ul>`,
        },
      },
    },
  })
}

function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  clerk.user = { firstName: 'Ada' }
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  )
  vi.mocked(getLinkedItemIds).mockResolvedValue([])
  vi.mocked(getAccounts).mockResolvedValue([checking])
  vi.mocked(getTransactions).mockResolvedValue([coffee])
  vi.mocked(getSpendingByCategory).mockResolvedValue(spending)
  vi.mocked(createLinkToken).mockResolvedValue('link-token')
  vi.mocked(exchangePublicToken).mockResolvedValue('saved-item')
  vi.stubGlobal('Plaid', {
    create: vi.fn((options: typeof linkOptions) => {
      linkOptions = options
      return { open: vi.fn(), destroy: vi.fn() }
    }),
  })
})

afterEach(() => vi.unstubAllGlobals())

describe('homepage balances', () => {
  it('waits for saved connections before showing onboarding', async () => {
    const wrapper = mountHome()
    expect(wrapper.text()).not.toContain('Start with an account.')
    expect(wrapper.find('[aria-label="Loading your accounts"]').exists()).toBe(true)

    await flushPromises()

    expect(wrapper.text()).toContain('Start with an account.')
    expect(getAccounts).not.toHaveBeenCalled()
  })

  it('restores a saved connection and displays its current balance', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(getAccounts).toHaveBeenCalledOnce()
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('.balance-card').text()).toContain('Balance')
    expect(wrapper.get('.balance-card').text()).toContain('$1,250.50')
    expect(wrapper.text()).not.toContain('Start with an account.')
  })

  it('replaces onboarding with the balance after Link succeeds', async () => {
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()

    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(exchangePublicToken).toHaveBeenCalledWith('public-token')
    expect(getAccounts).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('Start with an account.')
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
  })

  it('keeps a newly saved connection when balances fail and retries without relinking', async () => {
    vi.mocked(getAccounts).mockRejectedValueOnce(new Error('Plaid unavailable'))
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).not.toContain('Start with an account.')
    expect(wrapper.text()).toContain('We couldn’t load your accounts.')
    expect(wrapper.text()).not.toContain('could not be saved')
    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(exchangePublicToken).toHaveBeenCalledOnce()
  })

  it('does not show onboarding if the saved connections request fails', async () => {
    vi.mocked(getLinkedItemIds).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t load your connected accounts.')
    expect(wrapper.text()).not.toContain('Start with an account.')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
  })

  it('retries a failed exchange with the same public token instead of relinking', async () => {
    vi.mocked(exchangePublicToken).mockRejectedValueOnce(new Error('Plaid unavailable'))
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).toContain('could not be saved')

    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(exchangePublicToken).toHaveBeenLastCalledWith('public-token')
    expect(exchangePublicToken).toHaveBeenCalledTimes(2)
    expect(createLinkToken).toHaveBeenCalledOnce()
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
  })

  it('shows an unavailable balance instead of treating a null balance as zero', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([
      { ...checking, balances: { ...checking.balances, current: null, available: null } },
    ])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('—')
    expect(wrapper.get('.balance-amount').attributes('aria-label')).toBe('Balance unavailable')
    expect(wrapper.text()).not.toContain('$0.00')
  })
})

describe('homepage greeting', () => {
  afterEach(() => vi.useRealTimers())

  it('greets the signed-in user for the current time of day', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 18, 9, 0))
    const morning = mountHome()
    await flushPromises()
    expect(morning.get('h1').text()).toBe('Good morning, Ada')

    vi.setSystemTime(new Date(2026, 8, 18, 20, 0))
    const evening = mountHome()
    await flushPromises()
    expect(evening.get('h1').text()).toBe('Good evening, Ada')
  })

  it('drops the name rather than guessing when Clerk has no first name', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 18, 9, 0))
    clerk.user = { firstName: null, primaryEmailAddress: { emailAddress: 'a.b@example.com' } }
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Good morning')
  })

  it('greets without a name while Clerk is still loading', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 18, 14, 0))
    clerk.user = null
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Good afternoon')
  })
})

describe('homepage recent transactions', () => {
  it('lists recent transactions with money leaving the account as negative', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(getTransactions).toHaveBeenCalledWith(5, 'checking')
    expect(wrapper.get('.transaction-name').text()).toBe('Coffee Shop')
    expect(wrapper.get('.transaction-amount').text()).toBe('-$4.75')
    expect(wrapper.get('.transaction-meta').text()).toContain('Sep 17')
  })

  it('shows a refund as a positive amount', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactions).mockResolvedValue([{ ...coffee, amount: -4.75 }])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.transaction-amount').text()).toBe('+$4.75')
  })

  it('keeps balances visible when transactions fail to load', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactions).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.text()).toContain('We couldn’t load your recent transactions.')
  })
})

describe('homepage spending breakdown', () => {
  it('charts each category with a readable label', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    const slices = wrapper.findAll('.chart-stub li').map((slice) => slice.text())
    expect(slices).toEqual(['Rent & utilities: 1450', 'Food & drink: 82.5', 'Uncategorized: 12'])
    expect(wrapper.get('#spending-heading').text()).toContain('September')
    expect(wrapper.get('.spending-legend').text()).toContain('Rent & utilities')
    expect(wrapper.get('.spending-legend').text()).toContain('$1,450.00')
    expect(wrapper.get('.spending-total-amount').text()).toBe('$1,545')
  })

  it('labels categories Plaid adds later without a hardcoded name', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByCategory).mockResolvedValue({
      ...spending,
      total: 40,
      categories: [{ category: 'DIGITAL_ASSETS', amount: 40 }],
    })
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.chart-stub li').text()).toBe('Digital Assets: 40')
  })

  it('shows an empty state instead of a blank chart', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByCategory).mockResolvedValue({ ...spending, total: 0, categories: [] })
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('No spending recorded this month yet.')
    expect(wrapper.find('.chart-stub').exists()).toBe(false)
  })

  it('keeps the rest of the dashboard working when the breakdown fails', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByCategory).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('.transaction-name').text()).toBe('Coffee Shop')
    expect(wrapper.text()).toContain('We couldn’t load your spending breakdown.')
  })
})

describe('homepage account selector', () => {
  it('hides the selector when only one account is linked', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('[aria-label="Account"]').exists()).toBe(false)
  })

  it('switches the balance and reloads activity for the selected account', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('[aria-label="Account"]').text()).toContain('Checking ••1234')
    expect(getTransactions).toHaveBeenLastCalledWith(5, 'checking')
    expect(getSpendingByCategory).toHaveBeenLastCalledWith('checking')

    await wrapper.get('[aria-label="Account"]').setValue('savings')
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$8,400.00')
    expect(getTransactions).toHaveBeenLastCalledWith(5, 'savings')
    expect(getSpendingByCategory).toHaveBeenLastCalledWith('savings')
  })

  it('restores the last selected account on reload', async () => {
    localStorage.setItem('abacus.selectedAccountId', 'savings')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$8,400.00')
    expect(getTransactions).toHaveBeenCalledWith(5, 'savings')
  })
})
