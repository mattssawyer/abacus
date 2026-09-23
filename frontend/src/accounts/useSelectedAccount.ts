import { computed, getCurrentScope, onScopeDispose, readonly, ref } from 'vue'
import { getAccounts, type PlaidAccount } from '../api/PlaidService'

/** Shared by every page, so picking an account on one carries over to the next. */
const STORAGE_KEY = 'abacus.selectedAccountId'

/**
 * The user's accounts and which one they're viewing. The choice is remembered across pages and
 * visits; without one, the first checking or savings account is picked.
 */
export function useSelectedAccount() {
  const accounts = ref<PlaidAccount[]>([])
  const selectedAccountId = ref<string>()
  const loading = ref(false)
  const failed = ref(false)
  let disposed = false

  if (getCurrentScope()) onScopeDispose(() => (disposed = true))

  const selectedAccount = computed(() =>
    accounts.value.find((account) => account.account_id === selectedAccountId.value),
  )

  /** Loads accounts and resolves the selection. On failure, keeps what was already loaded. */
  async function load() {
    loading.value = true
    failed.value = false
    try {
      const loaded = await getAccounts()
      if (disposed) return
      accounts.value = loaded
      selectedAccountId.value = chooseAccount(loaded, selectedAccountId.value)
      if (selectedAccountId.value) remember(selectedAccountId.value)
    } catch {
      if (!disposed) failed.value = true
    } finally {
      if (!disposed) loading.value = false
    }
  }

  /** Switches to one of the loaded accounts. Returns whether the selection changed. */
  function select(accountId: string): boolean {
    if (accountId === selectedAccountId.value) return false
    if (!accounts.value.some((account) => account.account_id === accountId)) return false
    selectedAccountId.value = accountId
    remember(accountId)
    return true
  }

  return {
    accounts: readonly(accounts),
    selectedAccountId: readonly(selectedAccountId),
    selectedAccount,
    loading: readonly(loading),
    failed: readonly(failed),
    load,
    select,
  }
}

export function accountLabel(account: PlaidAccount): string {
  return account.mask ? `${account.name} ••${account.mask}` : account.name
}

function chooseAccount(accounts: PlaidAccount[], current: string | undefined): string | undefined {
  const isLoaded = (accountId: string | undefined) =>
    accountId != null && accounts.some((account) => account.account_id === accountId)

  if (isLoaded(current)) return current
  const remembered = recall()
  if (isLoaded(remembered)) return remembered
  return (accounts.find((account) => account.type === 'depository') ?? accounts[0])?.account_id
}

// Storage can be unavailable (private browsing, blocked site data); selection still works for
// this visit without it.
function recall(): string | undefined {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? undefined
  } catch {
    return undefined
  }
}

function remember(accountId: string) {
  try {
    localStorage.setItem(STORAGE_KEY, accountId)
  } catch {
    // Not remembered past this visit.
  }
}
