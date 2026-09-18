<script setup lang="ts">
import { UserButton, useUser } from '@clerk/vue'
import { ArrowRight, Landmark } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import SidebarLayout from 'primevue/sidebarlayout'
import SidebarMain from 'primevue/sidebarmain'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import {
  createLinkToken,
  exchangePublicToken,
  getLinkedItemIds,
  getAccounts,
  getTransactions,
  type PlaidAccount,
  type PlaidTransaction,
} from '../api/PlaidService'

const RECENT_TRANSACTION_COUNT = 5

const linking = ref(false)
const linkError = ref('')
const initialLoading = ref(true)
const loadingAccounts = ref(false)
const loadingTransactions = ref(false)
const connectionError = ref('')
const balanceError = ref('')
const transactionsError = ref('')
const itemIds = ref<string[]>([])
const accounts = ref<PlaidAccount[]>([])
const transactions = ref<PlaidTransaction[]>([])
const selectedAccountId = ref<string>()
const pendingPublicToken = ref<string>()
const { user } = useUser()
const hasConnections = computed(() => itemIds.value.length > 0)
const greeting = computed(() => {
  const hour = new Date().getHours()
  const timeOfDay = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'
  const firstName = user.value?.firstName
  return firstName ? `Good ${timeOfDay}, ${firstName}` : `Good ${timeOfDay}`
})
const selectedAccount = computed(() =>
  accounts.value.find((account) => account.account_id === selectedAccountId.value),
)
let handler: ReturnType<Window['Plaid']['create']> | undefined
let disposed = false

onMounted(loadConnections)
onUnmounted(() => {
  disposed = true
  handler?.destroy()
})

function formatBalance(amount: number | null) {
  if (amount === null) return '—'
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}

// Plaid reports money leaving the account as positive, which reads backwards in a ledger.
function formatTransactionAmount(transaction: PlaidTransaction) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: transaction.iso_currency_code ?? 'USD',
    signDisplay: 'exceptZero',
  }).format(-transaction.amount)
}

// Plaid dates are calendar days, so parse them locally instead of as UTC instants.
function formatTransactionDate(date: string) {
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(
    new Date(`${date}T00:00:00`),
  )
}

function transactionLabel(transaction: PlaidTransaction) {
  return transaction.merchant_name ?? transaction.name ?? 'Transaction'
}

async function loadConnections() {
  initialLoading.value = true
  connectionError.value = ''
  try {
    const savedItemIds = await getLinkedItemIds()
    if (disposed) return
    itemIds.value = savedItemIds
    await Promise.all([loadAccounts(), loadTransactions()])
  } catch {
    if (!disposed)
      connectionError.value = 'We couldn’t load your connected accounts. Please try again.'
  } finally {
    if (!disposed) initialLoading.value = false
  }
}

async function loadAccounts() {
  if (!itemIds.value.length) return
  loadingAccounts.value = true
  balanceError.value = ''
  try {
    const results = await Promise.allSettled(itemIds.value.map((itemId) => getAccounts(itemId)))
    if (disposed) return
    accounts.value = results.flatMap((result) =>
      result.status === 'fulfilled' ? result.value : [],
    )
    if (results.some((result) => result.status === 'rejected')) {
      balanceError.value = 'Some account balances couldn’t be loaded. Please try again.'
    }
    if (!selectedAccount.value) {
      selectedAccountId.value = (
        accounts.value.find((account) => account.type === 'depository') ?? accounts.value[0]
      )?.account_id
    }
  } finally {
    if (!disposed) loadingAccounts.value = false
  }
}

async function loadTransactions() {
  if (!itemIds.value.length) return
  loadingTransactions.value = true
  transactionsError.value = ''
  try {
    const recent = await getTransactions(RECENT_TRANSACTION_COUNT)
    if (!disposed) transactions.value = recent
  } catch {
    if (!disposed) transactionsError.value = 'We couldn’t load your recent transactions.'
  } finally {
    if (!disposed) loadingTransactions.value = false
  }
}

async function finishLink(publicToken: string) {
  linking.value = true
  linkError.value = ''
  pendingPublicToken.value = publicToken
  try {
    const itemId = await exchangePublicToken(publicToken)
    if (disposed) return
    pendingPublicToken.value = undefined
    if (!itemIds.value.includes(itemId)) itemIds.value.push(itemId)
    await Promise.all([loadAccounts(), loadTransactions()])
  } catch {
    if (!disposed) linkError.value = 'Your account connection could not be saved. Please try again.'
  } finally {
    if (!disposed) linking.value = false
  }
}

function retryLink() {
  if (pendingPublicToken.value) void finishLink(pendingPublicToken.value)
  else void openPlaidLink()
}

async function openPlaidLink() {
  if (linking.value) return
  linking.value = true
  linkError.value = ''

  try {
    if (!window.Plaid) throw new Error('Plaid Link unavailable')
    const token = await createLinkToken()
    if (disposed) return
    handler?.destroy()
    handler = window.Plaid.create({
      token,
      onSuccess(publicToken) {
        if (disposed) return
        void finishLink(publicToken)
      },
      onExit(error) {
        if (disposed) return
        linking.value = false
        if (error) linkError.value = 'Unable to connect your account. Please try again.'
      },
    })
    handler.open()
  } catch {
    if (disposed) return
    linkError.value = 'Unable to open account connection. Please try again.'
    linking.value = false
  }
}
</script>

<template>
  <SidebarLayout>
    <AppSidebar />
    <SidebarMain as="div" class="home-page">
      <header class="page-header">
        <h1>Home</h1>
        <UserButton />
      </header>
      <main class="page-content" aria-label="Dashboard">
        <div class="overview-heading">
          <div>
            <h2>{{ greeting }}</h2>
          </div>
        </div>

        <Card
          v-if="initialLoading"
          class="balance-card"
          role="status"
          aria-label="Loading your accounts"
          aria-busy="true"
        >
          <template #title>
            <h3 class="card-label">Balance</h3>
          </template>
          <template #content>
            <Skeleton width="min(100%, 20rem)" height="5rem" />
          </template>
        </Card>

        <div v-else-if="connectionError" class="account-notice">
          <Message severity="error">{{ connectionError }}</Message>
          <Button
            label="Try again"
            severity="secondary"
            class="retry-button"
            @click="loadConnections"
          />
        </div>

        <Card
          v-else-if="hasConnections"
          class="balance-card"
          role="region"
          aria-labelledby="balance-heading"
          :aria-busy="loadingAccounts"
        >
          <template #title>
            <h3 id="balance-heading" class="card-label">Balance</h3>
          </template>
          <template #content>
            <div v-if="loadingAccounts" role="status" aria-label="Loading balances">
              <Skeleton width="min(100%, 20rem)" height="5rem" />
            </div>
            <p
              v-else
              class="balance-amount"
              aria-live="polite"
              aria-atomic="true"
              :aria-label="
                selectedAccount?.balances.current == null ? 'Balance unavailable' : undefined
              "
            >
              {{ formatBalance(selectedAccount?.balances.current ?? null) }}
            </p>
          </template>
        </Card>

        <section v-else class="account-prompt" aria-labelledby="account-prompt-heading">
          <div class="account-prompt-content">
            <div class="account-icon" aria-hidden="true">
              <Landmark :size="24" :stroke-width="1.5" />
            </div>
            <h3 id="account-prompt-heading">Start with an account.</h3>
            <p>Connect a bank or credit card account to bring your finances into view.</p>
            <Button
              :label="linking ? 'Connecting…' : 'Add an account'"
              :loading="linking"
              :disabled="linking"
              :aria-busy="linking"
              @click="openPlaidLink"
            >
              <template #icon v-if="!linking">
                <ArrowRight :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <span class="connection-note">Connect through Plaid</span>
          </div>
        </section>
        <div
          v-if="
            hasConnections &&
            !initialLoading &&
            !loadingAccounts &&
            (balanceError || !accounts.length)
          "
          class="account-notice"
        >
          <Message :severity="balanceError ? 'error' : 'secondary'">
            {{ balanceError || 'Balance unavailable.' }}
          </Message>
          <Button
            label="Try again"
            severity="secondary"
            class="retry-button"
            @click="loadAccounts"
          />
        </div>

        <Card
          v-if="hasConnections && !initialLoading && !connectionError"
          class="transactions-card"
          role="region"
          aria-labelledby="recent-transactions-heading"
          :aria-busy="loadingTransactions"
        >
          <template #title>
            <h3 id="recent-transactions-heading" class="card-label">Recent transactions</h3>
          </template>
          <template #content>
            <div
              v-if="loadingTransactions"
              class="transactions-loading"
              role="status"
              aria-label="Loading recent transactions"
            >
              <Skeleton v-for="row in RECENT_TRANSACTION_COUNT" :key="row" height="2.5rem" />
            </div>

            <div v-else-if="transactionsError" class="account-notice">
              <Message severity="error">{{ transactionsError }}</Message>
              <Button
                label="Try again"
                severity="secondary"
                class="retry-button"
                @click="loadTransactions"
              />
            </div>

            <p v-else-if="!transactions.length" class="transactions-empty">
              No transactions yet. They’ll appear here once your bank sends them.
            </p>

            <ul v-else class="transactions-list">
              <li
                v-for="transaction in transactions"
                :key="transaction.transaction_id"
                class="transaction-row"
              >
                <img
                  v-if="transaction.logo_url"
                  class="transaction-logo"
                  :src="transaction.logo_url"
                  alt=""
                />
                <span v-else class="transaction-logo transaction-logo-fallback" aria-hidden="true">
                  {{ transactionLabel(transaction).charAt(0) }}
                </span>
                <span class="transaction-details">
                  <span class="transaction-name">{{ transactionLabel(transaction) }}</span>
                  <span class="transaction-meta">
                    {{ formatTransactionDate(transaction.date) }}
                    <template v-if="transaction.pending"> · Pending</template>
                  </span>
                </span>
                <span
                  class="transaction-amount"
                  :class="{ 'transaction-amount-inflow': transaction.amount < 0 }"
                >
                  {{ formatTransactionAmount(transaction) }}
                </span>
              </li>
            </ul>
          </template>
        </Card>
        <div v-if="linkError" class="account-notice">
          <Message severity="error">{{ linkError }}</Message>
          <Button
            label="Try again"
            severity="secondary"
            class="retry-button"
            :loading="linking"
            :disabled="linking"
            @click="retryLink"
          />
        </div>
      </main>
    </SidebarMain>
  </SidebarLayout>
</template>

<style scoped>
.home-page {
  min-width: 0;
  padding: 0;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  min-height: 4.5rem;
  padding-inline: clamp(1.25rem, 4vw, 3rem);
  border-bottom: 1px solid var(--app-border);
}

h1 {
  font-size: 0.875rem;
  font-weight: 500;
}

.page-content {
  max-width: 68rem;
  padding: clamp(1.25rem, 4vw, 3rem);
}

.overview-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 1.25rem;
  margin-bottom: 1.75rem;
}

h2 {
  font-size: clamp(1.375rem, 3vw, 1.75rem);
  font-weight: 550;
  line-height: 1.25;
  letter-spacing: -0.045em;
}

.overview-heading p {
  margin-top: 0.5rem;
  color: var(--app-muted);
}

.balance-card,
.transactions-card,
.account-prompt {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow);
}

.balance-card :deep(.p-card-body) {
  padding: clamp(1.75rem, 4vw, 2.75rem);
}

.transactions-card :deep(.p-card-body) {
  padding: clamp(1.25rem, 3vw, 2rem);
}

.transactions-card {
  margin-top: 1rem;
}

.card-label {
  color: var(--app-muted);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: -0.005em;
}

.transactions-loading {
  display: grid;
  gap: 0.75rem;
}

.transactions-empty {
  color: var(--app-muted);
  line-height: 1.65;
}

.transactions-list {
  display: grid;
  gap: 0.25rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.transaction-row {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  padding: 0.625rem 0;
  border-bottom: 1px solid var(--app-border);
}

.transaction-row:last-child {
  border-bottom: none;
}

.transaction-logo {
  flex: none;
  display: grid;
  width: 2rem;
  height: 2rem;
  place-items: center;
  object-fit: cover;
  background: var(--app-sidebar);
  border: 1px solid var(--app-border);
  border-radius: 50%;
}

.transaction-logo-fallback {
  color: var(--app-muted);
  font-size: 0.8125rem;
  font-weight: 550;
  text-transform: uppercase;
}

.transaction-details {
  display: grid;
  gap: 0.125rem;
  min-width: 0;
}

.transaction-name {
  overflow: hidden;
  color: var(--app-text);
  font-size: 0.875rem;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.transaction-meta {
  color: var(--app-muted);
  font-size: 0.75rem;
}

.transaction-amount {
  margin-left: auto;
  padding-left: 0.5rem;
  color: var(--app-text);
  font-size: 0.875rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.transaction-amount-inflow {
  color: var(--p-green-600);
}

.balance-amount {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: clamp(2.75rem, 6vw, 4.25rem);
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
  letter-spacing: -0.055em;
}

.retry-button {
  margin-top: 1rem;
}

.account-prompt {
  display: grid;
  min-height: 25rem;
  place-items: center;
  padding: 3rem 1.5rem;
}

.account-prompt-content {
  width: min(100%, 23rem);
  text-align: center;
}

.account-icon {
  display: grid;
  width: 3rem;
  height: 3rem;
  place-items: center;
  margin: 0 auto 1.5rem;
  background: var(--app-sidebar);
  color: var(--p-surface-700);
  border: 1px solid var(--app-border);
  border-radius: 0.875rem;
}

h3 {
  font-size: 1.25rem;
  font-weight: 550;
  letter-spacing: -0.035em;
}

.account-prompt p {
  margin: 0.75rem 0 1.5rem;
  color: var(--app-muted);
  line-height: 1.65;
}

.connection-note {
  display: block;
  margin-top: 0.875rem;
  font-size: 0.75rem;
  color: var(--app-muted);
}

.account-notice {
  margin-top: 1rem;
  text-align: left;
}

@media (max-width: 640px) {
  .account-prompt {
    min-height: 22rem;
    padding: 2rem 1rem;
  }
}
</style>
