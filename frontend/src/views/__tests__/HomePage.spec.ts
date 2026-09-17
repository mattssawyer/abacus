import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import HomePage from '../HomePage.vue'
import {
  createLinkToken,
  exchangePublicToken,
  getAccounts,
  getLinkedItemIds,
  type PlaidAccount,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  createLinkToken: vi.fn(),
  exchangePublicToken: vi.fn(),
  getAccounts: vi.fn(),
  getLinkedItemIds: vi.fn(),
}))
vi.mock('@clerk/vue', () => ({ UserButton: { template: '<div />' } }))
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

let linkOptions: Parameters<Window['Plaid']['create']>[0]

function mountHome() {
  return mount(HomePage, {
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        AppSidebar: true,
        SidebarLayout: { template: '<div><slot /></div>' },
        SidebarMain: { template: '<div><slot /></div>' },
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

    expect(getAccounts).toHaveBeenCalledWith('saved-item')
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('.balance-card').text()).toBe('$1,250.50')
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
    expect(getAccounts).toHaveBeenCalledWith('saved-item')
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
    expect(wrapper.text()).toContain('Some account balances couldn’t be loaded.')
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
