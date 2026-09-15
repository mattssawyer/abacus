<script setup lang="ts">
import { useAuth } from "@clerk/vue"
import HomePage from "./components/HomePage.vue"
import SignInPage from "./components/SignInPage.vue"
import { setAccessTokenProvider } from "./api/client"

const { getToken, isSignedIn } = useAuth()

setAccessTokenProvider(async () => (await getToken.value()) ?? null)
</script>

<template>
  <SignInPage v-if="!isSignedIn" />
  <HomePage v-else />
</template>
