import { apiClient } from './client'

export interface BalancePoint {
  /** YYYY-MM-DD */
  date: string
  value: number
}

export interface AccountChange {
  date: string
  account_id: string
  name: string
}

export interface BalanceHistory {
  /** One point per day, from the first balance snapshot (or the range start) through today. */
  net_worth: BalancePoint[]
  /** One series per investment account that hasn't been dropped. */
  accounts: { account_id: string; points: BalancePoint[] }[]
  accounts_added: AccountChange[]
  accounts_dropped: AccountChange[]
  /** Accounts net worth can't count, such as ones not in US dollars. */
  left_out_of_net_worth: string[]
}

/** Daily balances from `from` (YYYY-MM-DD), or from the start when omitted. */
export async function getBalanceHistory(from?: string): Promise<BalanceHistory> {
  const { data } = await apiClient.get<BalanceHistory>('/investments/history', {
    params: from ? { from } : undefined,
  })
  return data
}
