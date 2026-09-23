import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAccounts, type PlaidAccount } from '../api/PlaidService'
import { accountLabel, useSelectedAccount } from './useSelectedAccount'

vi.mock('../api/PlaidService', () => ({
  getAccounts: vi.fn(),
}))

const STORAGE_KEY = 'abacus.selectedAccountId'

function account(accountId: string, type = 'depository', mask: string | null = null): PlaidAccount {
  return {
    account_id: accountId,
    balances: { available: null, current: null, iso_currency_code: 'USD', limit: null },
    mask,
    name: accountId,
    official_name: null,
    subtype: null,
    type,
  }
}

const card = account('card', 'credit')
const checking = account('checking')
const savings = account('savings')

beforeEach(() => {
  localStorage.clear()
  vi.mocked(getAccounts).mockResolvedValue([card, checking, savings])
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useSelectedAccount', () => {
  it('starts on the first checking or savings account', async () => {
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBe('checking')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('checking')
  })

  it('falls back to the first account when none are checking or savings', async () => {
    vi.mocked(getAccounts).mockResolvedValue([card])
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccount.value).toEqual(card)
  })

  it('restores the account picked on an earlier visit', async () => {
    localStorage.setItem(STORAGE_KEY, 'savings')
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBe('savings')
  })

  it('ignores a remembered account that is no longer linked', async () => {
    localStorage.setItem(STORAGE_KEY, 'closed-account')
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBe('checking')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('checking')
  })

  it('remembers a new selection for other pages', async () => {
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.select('savings')).toBe(true)

    expect(selection.selectedAccountId.value).toBe('savings')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('savings')
  })

  it('refuses accounts that are not loaded and reports no change for the current one', async () => {
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.select('checking')).toBe(false)
    expect(selection.select('someone-elses-account')).toBe(false)
    expect(selection.selectedAccountId.value).toBe('checking')
  })

  it('keeps the current selection when accounts reload', async () => {
    const selection = useSelectedAccount()
    await selection.load()
    selection.select('savings')
    localStorage.clear()

    await selection.load()

    expect(selection.selectedAccountId.value).toBe('savings')
  })

  it('keeps loaded accounts and reports the failure when a reload fails', async () => {
    const selection = useSelectedAccount()
    await selection.load()
    vi.mocked(getAccounts).mockRejectedValueOnce(new Error('Server unavailable'))

    await selection.load()

    expect(selection.failed.value).toBe(true)
    expect(selection.loading.value).toBe(false)
    expect(selection.accounts.value).toHaveLength(3)
    expect(selection.selectedAccountId.value).toBe('checking')
  })

  it('still selects an account when storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBe('checking')
    expect(selection.select('savings')).toBe(true)
  })

  it('ignores accounts that arrive after the page is gone', async () => {
    const scope = effectScope()
    const selection = scope.run(() => useSelectedAccount())!
    const loading = selection.load()
    scope.stop()
    await loading

    expect(selection.accounts.value).toEqual([])
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })
})

describe('accountLabel', () => {
  it('adds the last digits when Plaid has them', () => {
    expect(accountLabel(account('Checking', 'depository', '1234'))).toBe('Checking ••1234')
    expect(accountLabel(account('Checking'))).toBe('Checking')
  })
})
