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

interface AccountsResponse {
  accounts: PlaidAccount[]
}

export async function createLinkToken(): Promise<string> {
  const { data } = await apiClient.post<{ link_token: string }>('/plaid/create-link-token')
  return data.link_token
}

export async function savePublicToken(publicToken: string): Promise<void> {
  await apiClient.post('/plaid/public-tokens', { publicToken })
}

export async function exchangePublicToken(): Promise<string> {
  const { data } = await apiClient.post<{ item_id: string }>('/plaid/exchange-public-token')
  return data.item_id
}

export async function getLinkedItemIds(): Promise<string[]> {
  const { data } = await apiClient.get<{ item_ids: string[] }>('/plaid/items')
  return data.item_ids
}

export async function getAccounts(itemId: string): Promise<PlaidAccount[]> {
  const { data } = await apiClient.get<AccountsResponse>(
    `/plaid/items/${encodeURIComponent(itemId)}/accounts`,
  )

  return data.accounts
}
