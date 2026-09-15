<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { onMounted, ref } from 'vue'
import {
  createLinkToken,
  exchangePublicToken,
  getAccounts,
  savePublicToken,
  type PlaidAccount,
} from '../api/PlaidService'

const loading = ref(false)
const error = ref('')
const success = ref('')
const accounts = ref<PlaidAccount[]>([])

async function openPlaidLink(): Promise<void> {
  loading.value = true
  error.value = ''
  success.value = ''

  try {
    const linkToken = await createLinkToken()

    if (!window.Plaid) throw new Error('Plaid Link failed to load')

    const handler = window.Plaid.create({
      token: linkToken,
      onSuccess(publicToken) {
        void finishLink(publicToken)
      },
      onExit(linkError) {
        if (linkError) error.value = 'Plaid Link closed with an error'
      },
    })
    handler.open()
  } catch {
    error.value = 'Unable to start Plaid Link'
  } finally {
    loading.value = false
  }
}

async function finishLink(publicToken: string): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    await savePublicToken(publicToken)
    const itemId = await exchangePublicToken()
    sessionStorage.setItem('plaid_item_id', itemId)
    accounts.value = await getAccounts(itemId)
    success.value = 'Bank linked and accounts loaded'
  } catch {
    error.value = 'Bank linked, but accounts could not be loaded'
  } finally {
    loading.value = false
  }
}

async function loadSavedAccounts(): Promise<void> {
  const itemId = sessionStorage.getItem('plaid_item_id')
  if (!itemId) return
  loading.value = true
  try {
    accounts.value = await getAccounts(itemId)
  } catch {
    sessionStorage.removeItem('plaid_item_id')
  } finally {
    loading.value = false
  }
}

function formatBalance(amount: number | null, currency: string | null): string {
  if (amount === null) return 'Unavailable'
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency ?? 'USD' }).format(amount)
}

onMounted(() => void loadSavedAccounts())
</script>

<template>
  <main class="dashboard">
    <header class="dashboard__header"><h1>Abacus</h1><UserButton /></header>
    <section class="dashboard__welcome">
      <div>
        <p class="eyebrow">Your financial overview</p>
        <h2>Everything, in one place.</h2>
        <p>Connect an account to start building your complete financial picture.</p>
      </div>
      <button :disabled="loading" @click="openPlaidLink">{{ loading ? 'Loading…' : 'Connect an account' }}</button>
    </section>
    <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>
    <p v-if="success" class="notice notice--success" role="status">{{ success }}</p>
    <section v-if="accounts.length" class="accounts">
      <h2>Connected accounts</h2>
      <ul>
        <li v-for="account in accounts" :key="account.account_id">
          <h3>{{ account.official_name ?? account.name }}</h3>
          <p>{{ account.subtype ?? account.type }} ending in {{ account.mask ?? 'unknown' }}</p>
          <p>Current: {{ formatBalance(account.balances.current, account.balances.iso_currency_code) }}</p>
          <p v-if="account.balances.available !== null">Available: {{ formatBalance(account.balances.available, account.balances.iso_currency_code) }}</p>
        </li>
      </ul>
    </section>
    <section v-else-if="!loading" class="dashboard__empty"><p>No accounts connected yet.</p></section>
  </main>
</template>
