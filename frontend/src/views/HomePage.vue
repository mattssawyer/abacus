<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { ArrowRight, Landmark, Plus } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import Select from 'primevue/select'
import SidebarLayout from 'primevue/sidebarlayout'
import SidebarMain from 'primevue/sidebarmain'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import {
  createLinkToken,
  savePublicToken,
  exchangePublicToken,
  getLinkedItemIds,
  getAccounts,
  type PlaidAccount,
} from '../api/PlaidService'

const linking = ref(false)
const linkError = ref('')
const initialLoading = ref(true)
const loadingAccounts = ref(false)
const connectionError = ref('')
const balanceError = ref('')
const itemIds = ref<string[]>([])
const accounts = ref<PlaidAccount[]>([])
const selectedAccountId = ref<string>()
const hasConnections = computed(() => itemIds.value.length > 0)
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

function accountLabel(account: PlaidAccount) {
  return account.mask ? `${account.name} · ${account.mask}` : account.name
}

function formatBalance(amount: number | null) {
  if (amount === null) return '—'
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}

async function loadConnections() {
  initialLoading.value = true
  connectionError.value = ''
  try {
    const savedItemIds = await getLinkedItemIds()
    if (disposed) return
    itemIds.value = savedItemIds
    await loadAccounts()
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

async function finishLink(publicToken: string) {
  try {
    await savePublicToken(publicToken)
    const itemId = await exchangePublicToken()
    if (disposed) return
    if (!itemIds.value.includes(itemId)) itemIds.value.push(itemId)
    await loadAccounts()
  } catch {
    if (!disposed) linkError.value = 'Your account connection could not be saved. Please try again.'
  } finally {
    if (!disposed) linking.value = false
  }
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
            <h2>Your financial overview</h2>
          </div>
        </div>

        <Card
          v-if="initialLoading"
          class="balance-card"
          role="status"
          aria-label="Loading your accounts"
          aria-busy="true"
        >
          <template #content>
            <Skeleton width="min(100%, 16rem)" height="4rem" />
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
          aria-label="Balance"
          :aria-busy="loadingAccounts"
        >
          <template #content>
            <div v-if="loadingAccounts" role="status" aria-label="Loading balances">
              <Skeleton width="min(100%, 16rem)" height="4rem" />
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
        <Message v-if="linkError" severity="error" class="account-notice">{{ linkError }}</Message>
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
  margin-inline: auto;
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
.account-prompt {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow);
}

.balance-card :deep(.p-card-body) {
  padding: clamp(1.25rem, 3vw, 2rem);
}

.overview-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  max-width: 100%;
}

.account-select {
  width: min(100%, 19rem);
  font-size: 0.8125rem;
}

.balance-amount {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: clamp(2.25rem, 5vw, 3.5rem);
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
