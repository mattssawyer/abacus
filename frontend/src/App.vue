<script setup lang="ts">
import { useAuth } from '@clerk/vue'
import { RouterView } from 'vue-router'
import SignInPage from './views/SignInPage.vue'
import { setAccessTokenProvider } from './api/client'

const { getToken, isSignedIn, isLoaded } = useAuth()

setAccessTokenProvider(async () => (await getToken.value()) ?? null)
</script>

<template>
  <template v-if="isLoaded">
    <SignInPage v-if="!isSignedIn" />
    <RouterView v-else />
  </template>
  <main v-else class="session-loading" role="status" aria-live="polite">Loading…</main>
</template>

<style scoped>
.session-loading {
  display: grid;
  min-height: 100svh;
  place-items: center;
  color: var(--app-text-secondary);
}
</style>
