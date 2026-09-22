import { apiClient } from './client'

export interface PlaidAccount {
  account_id: string
  balances: {
    available: number | null
    current: number | null
    iso_currency_code: string | null
    unofficial_currency_code?: string | null
    limit: number | null
  }
  mask: string | null
  name: string
  official_name: string | null
  subtype: string | null
  type: string
}

export interface PlaidTransaction {
  transaction_id: string
  account_id: string
  /** Plaid convention: positive when money leaves the account. */
  amount: number
  iso_currency_code: string | null
  date: string
  name: string | null
  merchant_name: string | null
  logo_url: string | null
  pending: boolean
  category: string | null
}

export interface RecurringStream {
  stream_id: string
  account_id: string
  merchant_name: string | null
  description: string | null
  amount: number
  iso_currency_code: string | null
  frequency: string
  next_date: string | null
  last_date: string | null
  is_inflow: boolean
  category: string | null
  /** Plaid detailed personal finance category, e.g. RENT_AND_UTILITIES_TELEPHONE. */
  category_detailed: string | null
}

export interface CategorySpend {
  /** Plaid primary personal finance category, or UNCATEGORIZED. */
  category: string
  amount: number
}

export interface SpendingByCategory {
  start: string
  end: string
  total: number
  categories: CategorySpend[]
}

interface AccountsResponse {
  accounts: PlaidAccount[]
}

export async function createLinkToken(): Promise<string> {
  const { data } = await apiClient.post<{ link_token: string }>('/plaid/create-link-token')
  return data.link_token
}

export async function exchangePublicToken(publicToken: string): Promise<string> {
  const { data } = await apiClient.post<{ item_id: string }>('/plaid/items', { publicToken })
  return data.item_id
}

export async function getLinkedItemIds(): Promise<string[]> {
  const { data } = await apiClient.get<{ item_ids: string[] }>('/plaid/items')
  return data.item_ids
}

export async function getAccounts(): Promise<PlaidAccount[]> {
  const { data } = await apiClient.get<AccountsResponse>('/plaid/accounts')
  return data.accounts
}

export async function getSpendingByCategory(accountId?: string): Promise<SpendingByCategory> {
  const { data } = await apiClient.get<SpendingByCategory>('/plaid/spending/by-category', {
    params: accountId ? { account_id: accountId } : undefined,
  })
  return data
}

export async function getTransactions(limit = 25, accountId?: string): Promise<PlaidTransaction[]> {
  const { data } = await apiClient.get<{ transactions: PlaidTransaction[] }>(
    '/plaid/transactions',
    {
      params: { limit, ...(accountId ? { account_id: accountId } : {}) },
    },
  )

  return data.transactions
}

export async function getRecurringTransactions(
  accountId?: string,
  limit?: number,
): Promise<RecurringStream[]> {
  const { data } = await apiClient.get<{ streams: RecurringStream[] }>(
    '/plaid/transactions/recurring',
    {
      params: {
        ...(accountId ? { account_id: accountId } : {}),
        ...(limit != null ? { limit } : {}),
      },
    },
  )

  return data.streams
}
