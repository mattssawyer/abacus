<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import SidebarLayout from 'primevue/sidebarlayout'
import SidebarMain from 'primevue/sidebarmain'
import AppSidebar from '../components/AppSidebar.vue'
import { createLinkToken, savePublicToken, exchangePublicToken } from '../api/PlaidService'

const linking = ref(false)
const error = ref('')
const success = ref('')
let handler: ReturnType<Window['Plaid']['create']> | undefined
let disposed = false

onUnmounted(() => {
  disposed = true
  handler?.destroy()
})

async function finishLink(publicToken: string) {
  try {
    await savePublicToken(publicToken)
    await exchangePublicToken()
    if (disposed) return
    success.value = 'Account connected successfully.'
  } catch {
    if (!disposed) error.value = 'Your account connection could not be saved. Please try again.'
  } finally {
    linking.value = false
  }
}

async function openPlaidLink() {
  if (linking.value) return
  linking.value = true
  error.value = ''
  success.value = ''

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
      onExit(linkError) {
        if (disposed) return
        linking.value = false
        if (linkError) error.value = 'Unable to connect your account. Please try again.'
      },
    })
    handler.open()
  } catch {
    error.value = 'Unable to open account connection. Please try again.'
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
      <main aria-label="Dashboard">
        <section class="account-prompt" aria-labelledby="account-prompt-heading">
          <h2 id="account-prompt-heading">Connect your accounts</h2>
          <p>Add a bank or credit card account with Plaid to get started.</p>
          <Button
            :label="linking ? 'Connecting…' : 'Add an account'"
            :loading="linking"
            :disabled="linking"
            :aria-busy="linking"
            @click="openPlaidLink"
          />
          <Message v-if="error" severity="error" class="account-notice">{{ error }}</Message>
          <Message v-if="success" severity="secondary" class="account-notice">{{
            success
          }}</Message>
        </section>
      </main>
    </SidebarMain>
  </SidebarLayout>
</template>

<style scoped>
.home-page {
  min-width: 0;
  padding: clamp(1rem, 3vw, 2rem);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

h1,
h2 {
  font-weight: 600;
  letter-spacing: -0.025em;
}

h1 {
  font-size: 1.25rem;
}

h2 {
  font-size: 1.5rem;
}

.account-prompt {
  max-width: 28rem;
  margin: clamp(3rem, 10vh, 7rem) auto;
  text-align: center;
}

.account-prompt p {
  margin: 0.75rem 0 1.5rem;
  color: var(--p-text-muted-color);
}

.account-notice {
  margin-top: 1rem;
}
</style>
