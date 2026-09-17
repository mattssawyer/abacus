<script setup lang="ts">
import { SignInButton, SignUpButton, useAuth } from '@clerk/vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Divider from 'primevue/divider'

const { isLoaded } = useAuth()
const logoUrl = `${import.meta.env.BASE_URL}logo.svg`
</script>

<template>
  <main class="sign-in-page" aria-labelledby="sign-in-title">
    <div class="sign-in-layout">
      <div class="sign-in-brand" aria-label="Abacus">
        <img :src="logoUrl" alt="" class="sign-in-logo" />
        <span>Abacus</span>
      </div>

      <Card class="sign-in-card">
        <template #title>
          <h1 id="sign-in-title">Your finances, together.</h1>
        </template>
        <template #subtitle> Sign in or create an account to get started </template>
        <template #content>
          <div class="sign-in-actions" :aria-busy="!isLoaded">
            <SignInButton>
              <Button
                label="Sign in"
                size="large"
                fluid
                :disabled="!isLoaded"
                :loading="!isLoaded"
              />
            </SignInButton>
            <Divider align="center"><span class="sign-in-secondary">New to Abacus?</span></Divider>
            <SignUpButton>
              <Button
                label="Create an account"
                severity="secondary"
                variant="outlined"
                size="large"
                fluid
                :disabled="!isLoaded"
              />
            </SignUpButton>
          </div>
        </template>
      </Card>
    </div>
  </main>
</template>

<style scoped>
.sign-in-page {
  display: grid;
  min-height: 100vh;
  min-height: 100svh;
  place-items: center;
  padding: 2rem 1rem;
  background: var(--p-surface-50);
  color: var(--p-text-color);
}

.sign-in-layout {
  width: min(100%, 28rem);
}

.sign-in-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  margin-bottom: 2rem;
  font-size: 1.125rem;
}

.sign-in-logo {
  height: 2rem;
}

.sign-in-brand span {
  font-weight: 550;
  letter-spacing: -0.035em;
}

.sign-in-card {
  --p-card-body-padding: clamp(1.5rem, 5vw, 2.5rem);
  --p-card-border-radius: var(--app-panel-radius);
  border: 1px solid var(--app-border);
}

h1 {
  font-size: clamp(1.5rem, 5vw, 1.75rem);
  font-weight: 550;
  line-height: 1.25;
  letter-spacing: -0.04em;
  margin-bottom: 0.5rem;
}

.sign-in-actions {
  padding-top: 1.25rem;
}

.sign-in-secondary {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}
</style>
