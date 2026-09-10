import { apiClient } from './client'

export interface PlaidAccount {
  account_id: string
  balances: {
    available: number | null
    current: number | null
    iso_currency_code: string | null
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

export async function exchangePublicToken(publicToken: string): Promise<string> {
  const { data } = await apiClient.post<{ item_id: string }>('/plaid/exchange-public-token', {
    publicToken,
  })

  return data.item_id
}

export async function getAccounts(itemId: string): Promise<PlaidAccount[]> {
  const { data } = await apiClient.get<AccountsResponse>(
    `/plaid/items/${encodeURIComponent(itemId)}/accounts`,
  )

  return data.accounts
}