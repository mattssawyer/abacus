<script setup lang="ts">
import { SignInButton, SignUpButton } from '@clerk/vue'
</script>

<template>
  <main class="auth-prompt">
    <section>
      <h1>Welcome to Abacus</h1>
      <p>Sign in or create an account to view your financial dashboard.</p>
      <div class="auth-prompt__actions">
        <SignUpButton><button>Create an account</button></SignUpButton>
        <SignInButton><button class="button--quiet">Sign in</button></SignInButton>
      </div>
    </section>
  </main>
</template>
