<script setup lang="ts">
import { UserButton, useUser } from '@clerk/vue'
import { Landmark, Plus } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { ChartOptions } from 'chart.js'
import Button from 'primevue/button'
import Chart from 'primevue/chart'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import {
  createLinkToken,
  exchangePublicToken,
  getLinkedItemIds,
  getAccounts,
  getSpendingByCategory,
  getTransactions,
  type PlaidAccount,
  type PlaidTransaction,
  type SpendingByCategory,
} from '../api/PlaidService'

const RECENT_TRANSACTION_COUNT = 5
const ACCOUNT_STORAGE_KEY = 'abacus.selectedAccountId'

// Plaid's primary personal finance categories, minus the income and transfer ones the
// server filters out as non-spending.
const CATEGORY_LABELS: Record<string, string> = {
  BANK_FEES: 'Bank fees',
  ENTERTAINMENT: 'Entertainment',
  FOOD_AND_DRINK: 'Food & drink',
  GENERAL_MERCHANDISE: 'Shopping',
  GENERAL_SERVICES: 'Services',
  GOVERNMENT_AND_NON_PROFIT: 'Government & charity',
  HOME_IMPROVEMENT: 'Home improvement',
  LOAN_PAYMENTS: 'Loan payments',
  MEDICAL: 'Medical',
  PERSONAL_CARE: 'Personal care',
  RENT_AND_UTILITIES: 'Rent & utilities',
  TRANSPORTATION: 'Transportation',
  TRAVEL: 'Travel',
  UNCATEGORIZED: 'Uncategorized',
}

// Category palette borrowed from Maybe: saturated enough to tell slices apart, muted enough
// to sit on a neutral page.
const CATEGORY_COLORS = [
  '#6471eb',
  '#4da568',
  '#e99537',
  '#db5a54',
  '#df4e92',
  '#c44fe9',
  '#61c9ea',
  '#eb5429',
  '#805dee',
  '#6ad28a',
  '#9e9e9e',
]

const linking = ref(false)
const linkError = ref('')
const initialLoading = ref(true)
const loadingAccounts = ref(false)
const loadingTransactions = ref(false)
const loadingSpending = ref(false)
const connectionError = ref('')
const balanceError = ref('')
const transactionsError = ref('')
const spendingError = ref('')
const itemIds = ref<string[]>([])
const accounts = ref<PlaidAccount[]>([])
const transactions = ref<PlaidTransaction[]>([])
const spending = ref<SpendingByCategory>()
const selectedAccountId = ref<string>()
const pendingPublicToken = ref<string>()
const { user } = useUser()
const hasConnections = computed(() => itemIds.value.length > 0)
const hasMultipleAccounts = computed(() => accounts.value.length > 1)
const greeting = computed(() => {
  const hour = new Date().getHours()
  const timeOfDay = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'
  const firstName = user.value?.firstName
  return firstName ? `Good ${timeOfDay}, ${firstName}` : `Good ${timeOfDay}`
})
const selectedAccount = computed(() =>
  accounts.value.find((account) => account.account_id === selectedAccountId.value),
)
const selectedAccountLabel = computed(() => {
  const account = selectedAccount.value
  return account ? accountLabel(account) : ''
})
const spendingCategories = computed(() => spending.value?.categories ?? [])
const spendingTotal = computed(() => spending.value?.total ?? 0)
const spendingMonth = computed(() =>
  spending.value
    ? new Intl.DateTimeFormat('en-US', { month: 'long' }).format(
        new Date(`${spending.value.start}T00:00:00`),
      )
    : '',
)
const spendingLegend = computed(() =>
  spendingCategories.value.map((entry, index) => ({
    ...entry,
    label: categoryLabel(entry.category),
    color: CATEGORY_COLORS[index % CATEGORY_COLORS.length] as string,
  })),
)
const spendingChartData = computed(() => ({
  labels: spendingLegend.value.map((entry) => entry.label),
  datasets: [
    {
      data: spendingLegend.value.map((entry) => entry.amount),
      backgroundColor: spendingLegend.value.map((entry) => entry.color),
      hoverBackgroundColor: spendingLegend.value.map((entry) => entry.color),
      borderColor: '#ffffff',
      borderWidth: 3,
      hoverOffset: 0,
    },
  ],
}))
const spendingChartOptions: ChartOptions<'doughnut'> = {
  maintainAspectRatio: false,
  cutout: '76%',
  layout: { padding: 2 },
  plugins: {
    legend: { display: false },
    tooltip: {
      backgroundColor: '#171717',
      titleFont: { family: 'Geist Variable', weight: 500 },
      bodyFont: { family: 'Geist Variable' },
      padding: 10,
      cornerRadius: 8,
      displayColors: false,
      callbacks: { label: (context) => formatBalance(context.parsed) },
    },
  },
}
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

function formatWholeDollars(amount: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(amount)
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

function accountLabel(account: PlaidAccount) {
  return account.mask ? `${account.name} ••${account.mask}` : account.name
}

function transactionLabel(transaction: PlaidTransaction) {
  return transaction.merchant_name ?? transaction.name ?? 'Transaction'
}

// Plaid can add primary categories, so fall back to a readable form of whatever it sends.
function categoryLabel(category: string) {
  return (
    CATEGORY_LABELS[category] ??
    category
      .toLowerCase()
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ')
  )
}

async function loadConnections() {
  initialLoading.value = true
  connectionError.value = ''
  try {
    const savedItemIds = await getLinkedItemIds()
    if (disposed) return
    itemIds.value = savedItemIds
    await loadAccounts()
    if (disposed) return
    await Promise.all([loadTransactions(), loadSpending()])
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
    accounts.value = await getAccounts()
    if (!disposed) chooseAccount()
  } catch {
    if (!disposed) balanceError.value = 'We couldn’t load your accounts. Please try again.'
  } finally {
    if (!disposed) loadingAccounts.value = false
  }
}

function chooseAccount() {
  const stillValid = (accountId: string | undefined) =>
    Boolean(accountId && accounts.value.some((account) => account.account_id === accountId))

  if (stillValid(selectedAccountId.value)) {
    localStorage.setItem(ACCOUNT_STORAGE_KEY, selectedAccountId.value as string)
    return
  }

  const remembered = localStorage.getItem(ACCOUNT_STORAGE_KEY) ?? undefined
  selectedAccountId.value = stillValid(remembered)
    ? remembered
    : (accounts.value.find((account) => account.type === 'depository') ?? accounts.value[0])
        ?.account_id

  if (selectedAccountId.value) {
    localStorage.setItem(ACCOUNT_STORAGE_KEY, selectedAccountId.value)
  }
}

function onAccountChange(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLSelectElement)) return
  const accountId = target.value
  if (!accountId || accountId === selectedAccountId.value) return
  if (!accounts.value.some((account) => account.account_id === accountId)) return
  selectedAccountId.value = accountId
  localStorage.setItem(ACCOUNT_STORAGE_KEY, accountId)
  void Promise.all([loadTransactions(), loadSpending()])
}

async function loadSpending() {
  if (!itemIds.value.length) return
  loadingSpending.value = true
  spendingError.value = ''
  try {
    const summary = await getSpendingByCategory(selectedAccountId.value)
    if (!disposed) spending.value = summary
  } catch {
    if (!disposed) spendingError.value = 'We couldn’t load your spending breakdown.'
  } finally {
    if (!disposed) loadingSpending.value = false
  }
}

async function loadTransactions() {
  if (!itemIds.value.length) return
  loadingTransactions.value = true
  transactionsError.value = ''
  try {
    const recent = await getTransactions(RECENT_TRANSACTION_COUNT, selectedAccountId.value)
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
    await loadAccounts()
    if (disposed) return
    await Promise.all([loadTransactions(), loadSpending()])
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
  <div class="app-shell">
    <AppSidebar />
    <div class="home-page">
      <main class="page-content" aria-label="Dashboard">
        <div class="overview-heading">
          <div>
            <h1>{{ greeting }}</h1>
            <p>Here's what's happening with your finances</p>
          </div>
          <div class="overview-actions">
            <Button
              v-if="hasConnections"
              :label="linking ? 'Connecting…' : 'Add an account'"
              :loading="linking"
              :disabled="linking"
              :aria-busy="linking"
              class="add-account-button"
              @click="openPlaidLink"
            >
              <template #icon v-if="!linking">
                <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <UserButton />
          </div>
        </div>

        <section
          v-if="initialLoading"
          class="panel balance-card"
          role="status"
          aria-label="Loading your accounts"
          aria-busy="true"
        >
          <p class="card-label">Balance</p>
          <Skeleton width="min(100%, 20rem)" height="4.5rem" />
        </section>

        <div v-else-if="connectionError" class="account-notice">
          <Message severity="error">{{ connectionError }}</Message>
          <Button
            label="Try again"
            severity="secondary"
            class="retry-button"
            @click="loadConnections"
          />
        </div>

        <div v-else-if="hasConnections" class="dashboard-grid">
          <div class="dashboard-main">
            <section
              class="panel balance-card"
              role="region"
              aria-labelledby="balance-heading"
              :aria-busy="loadingAccounts"
            >
              <div class="balance-heading">
                <h2 id="balance-heading" class="card-label">Balance</h2>
                <select
                  v-if="hasMultipleAccounts"
                  class="account-select"
                  aria-label="Account"
                  :value="selectedAccountId"
                  :disabled="loadingAccounts"
                  @change="onAccountChange($event)"
                >
                  <option
                    v-for="account in accounts"
                    :key="account.account_id"
                    :value="account.account_id"
                  >
                    {{ accountLabel(account) }}
                  </option>
                </select>
              </div>
              <div v-if="loadingAccounts" role="status" aria-label="Loading balances">
                <Skeleton width="min(100%, 20rem)" height="4.5rem" />
              </div>
              <template v-else>
                <p
                  class="balance-amount"
                  aria-live="polite"
                  aria-atomic="true"
                  :aria-label="
                    selectedAccount?.balances.current == null ? 'Balance unavailable' : undefined
                  "
                >
                  {{ formatBalance(selectedAccount?.balances.current ?? null) }}
                </p>
                <p v-if="!hasMultipleAccounts && selectedAccountLabel" class="balance-account">
                  {{ selectedAccountLabel }}
                </p>
              </template>
            </section>

            <div
              v-if="!loadingAccounts && (balanceError || !accounts.length)"
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

            <section
              class="panel transactions-card"
              role="region"
              aria-labelledby="recent-transactions-heading"
              :aria-busy="loadingTransactions"
            >
              <h2 id="recent-transactions-heading" class="card-label">Recent transactions</h2>
              <div
                v-if="loadingTransactions"
                class="transactions-loading"
                role="status"
                aria-label="Loading recent transactions"
              >
                <Skeleton v-for="row in RECENT_TRANSACTION_COUNT" :key="row" height="2.75rem" />
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
                  <span
                    v-else
                    class="transaction-logo transaction-logo-fallback"
                    aria-hidden="true"
                  >
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
            </section>
          </div>

          <section
            class="panel spending-card"
            role="region"
            aria-labelledby="spending-heading"
            :aria-busy="loadingSpending"
          >
            <h2 id="spending-heading" class="card-label">
              Spending<template v-if="spendingMonth"> · {{ spendingMonth }}</template>
            </h2>
            <div
              v-if="loadingSpending"
              class="spending-chart"
              role="status"
              aria-label="Loading your spending breakdown"
            >
              <Skeleton width="100%" height="100%" border-radius="50%" />
            </div>

            <div v-else-if="spendingError" class="account-notice">
              <Message severity="error">{{ spendingError }}</Message>
              <Button
                label="Try again"
                severity="secondary"
                class="retry-button"
                @click="loadSpending"
              />
            </div>

            <p v-else-if="!spendingCategories.length" class="spending-empty">
              No spending recorded this month yet.
            </p>

            <div v-else class="spending-body">
              <div class="spending-chart">
                <Chart
                  type="doughnut"
                  :data="spendingChartData"
                  :options="spendingChartOptions"
                  class="spending-chart-canvas"
                  :aria-label="`Spending by category for ${spendingMonth}`"
                />
                <div class="spending-total" aria-hidden="true">
                  <span class="spending-total-amount">{{ formatWholeDollars(spendingTotal) }}</span>
                  <span class="spending-total-label">this month</span>
                </div>
              </div>
              <ul class="spending-legend">
                <li
                  v-for="entry in spendingLegend"
                  :key="entry.category"
                  class="spending-legend-row"
                >
                  <span
                    class="spending-swatch"
                    :style="{ backgroundColor: entry.color }"
                    aria-hidden="true"
                  />
                  <span class="spending-legend-label">{{ entry.label }}</span>
                  <span class="spending-legend-amount">{{ formatBalance(entry.amount) }}</span>
                </li>
              </ul>
            </div>
          </section>
        </div>

        <section v-else class="panel account-prompt" aria-labelledby="account-prompt-heading">
          <div class="account-prompt-content">
            <div class="account-icon" aria-hidden="true">
              <Landmark :size="22" :stroke-width="1.5" />
            </div>
            <h2 id="account-prompt-heading">Start with an account.</h2>
            <p>Connect a bank or credit card account to bring your finances into view.</p>
            <Button
              :label="linking ? 'Connecting…' : 'Add an account'"
              :loading="linking"
              :disabled="linking"
              :aria-busy="linking"
              @click="openPlaidLink"
            >
              <template #icon v-if="!linking">
                <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <span class="connection-note">Connect through Plaid</span>
          </div>
        </section>
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
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  align-items: stretch;
  min-height: 100svh;
}

.home-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.page-content {
  width: min(100%, 72rem);
  padding: 2rem clamp(1.25rem, 4vw, 2.5rem) 3rem;
}

.overview-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 1rem;
  margin-bottom: 1.75rem;
}

h1 {
  font-size: clamp(1.5rem, 3vw, 1.875rem);
  font-weight: 550;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.overview-heading p {
  margin-top: 0.35rem;
  color: var(--app-text-secondary);
}

.overview-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 0.75rem;
}

.overview-actions :deep(.cl-userButtonTrigger),
.overview-actions :deep(.cl-avatarBox) {
  width: 2rem;
  height: 2rem;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(17rem, 0.85fr);
  align-items: start;
  gap: 1rem;
}

.dashboard-main {
  display: grid;
  align-content: start;
  gap: 1rem;
  min-width: 0;
}

.dashboard-main > .account-notice {
  margin-top: 0;
}

.balance-card,
.transactions-card,
.spending-card,
.account-prompt {
  padding: 1.25rem 1.5rem 1.5rem;
}

.balance-card {
  padding: 1.5rem 1.75rem 1.75rem;
}

.spending-card {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
  min-height: 100%;
}

.spending-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1.25rem;
  min-height: 0;
}

.spending-chart {
  position: relative;
  flex: none;
  width: min(100%, 16.5rem);
  height: 16.5rem;
  margin: 0.5rem auto 0;
}

.spending-chart-canvas {
  height: 100%;
}

.spending-total {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  text-align: center;
  pointer-events: none;
}

.spending-total-amount {
  font-size: 1.25rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
}

.spending-total-label {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
}

.spending-legend {
  display: grid;
  gap: 0.125rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.spending-legend-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-height: 1.75rem;
}

.spending-swatch {
  flex: none;
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 50%;
}

.spending-legend-label {
  min-width: 0;
  overflow: hidden;
  color: var(--app-text);
  font-size: 0.8125rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spending-legend-amount {
  margin-left: auto;
  padding-left: 0.5rem;
  color: var(--app-text);
  font-size: 0.8125rem;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.spending-empty {
  color: var(--app-text-secondary);
  line-height: 1.65;
}

.card-label {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: -0.005em;
}

.balance-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.account-select {
  min-width: 11rem;
  max-width: 16rem;
  appearance: none;
  padding: 0.375rem 1.75rem 0.375rem 0.625rem;
  color: var(--app-text);
  background-color: var(--app-surface);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='none' stroke='%23737373' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.75' viewBox='0 0 24 24'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-position: right 0.4rem center;
  background-repeat: no-repeat;
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
  box-shadow: var(--app-shadow-xs);
  font: inherit;
  font-size: 0.8125rem;
  font-weight: 500;
}

.transactions-loading {
  display: grid;
  gap: 0.625rem;
  margin-top: 0.75rem;
}

.transactions-empty {
  margin-top: 0.75rem;
  color: var(--app-text-secondary);
  line-height: 1.65;
}

.transactions-list {
  display: grid;
  margin: 0.5rem 0 0;
  padding: 0;
  list-style: none;
}

.transaction-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-height: 3.25rem;
  padding: 0.625rem 0;
}

.transaction-row:not(:last-child)::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 2.75rem;
  height: 1px;
  background: var(--app-divider);
}

.transaction-logo {
  flex: none;
  display: grid;
  width: 2rem;
  height: 2rem;
  place-items: center;
  object-fit: cover;
  background: var(--app-inset);
  border-radius: 50%;
}

.transaction-logo-fallback {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  font-weight: 550;
  text-transform: uppercase;
}

.transaction-details {
  display: grid;
  gap: 0.0625rem;
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
  color: var(--app-text-secondary);
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
  color: var(--app-success);
}

.balance-amount {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: clamp(2.5rem, 6vw, 3.75rem);
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  letter-spacing: -0.05em;
}

.balance-account {
  margin-top: 0.375rem;
  color: var(--app-text-secondary);
  font-size: 0.875rem;
}

.retry-button {
  margin-top: 1rem;
}

.account-prompt {
  display: grid;
  min-height: 24rem;
  place-items: center;
  padding: 3rem 1.5rem;
}

.account-prompt-content {
  width: min(100%, 22rem);
  text-align: center;
}

.account-icon {
  display: grid;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  margin: 0 auto 1.25rem;
  background: var(--app-inset);
  color: var(--app-text-secondary);
  border-radius: var(--app-radius-control);
}

.account-prompt h2 {
  font-size: 1.25rem;
  font-weight: 550;
  letter-spacing: -0.03em;
}

.account-prompt p {
  margin: 0.5rem 0 1.25rem;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.connection-note {
  display: block;
  margin-top: 0.75rem;
  font-size: 0.75rem;
  color: var(--app-text-subdued);
}

.account-notice {
  margin-top: 1rem;
  text-align: left;
}

@media (max-width: 900px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .page-content {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  .overview-heading {
    margin-bottom: 1.25rem;
  }
}

@media (max-width: 640px) {
  .balance-card,
  .transactions-card,
  .spending-card {
    padding: 1.125rem 1.125rem 1.25rem;
  }

  .spending-chart {
    width: min(100%, 14.5rem);
    height: 14.5rem;
  }

  .account-prompt {
    min-height: 20rem;
    padding: 2rem 1rem;
  }
}
</style>
