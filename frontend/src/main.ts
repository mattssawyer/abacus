import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { clerkPlugin } from '@clerk/vue'
import PrimeVue from 'primevue/config'
import theme from './theme'

import App from './App.vue'
import router from './router'

import './assets/main.css'

const publishableKey = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY

if (!publishableKey) {
  throw new Error('VITE_CLERK_PUBLISHABLE_KEY is not set')
}

const app = createApp(App)

app.use(PrimeVue, {
  license: import.meta.env.VITE_PRIMEUI_LICENSE_KEY,
  theme: {
    preset: theme,
    options: { darkModeSelector: false },
  },
})
app.use(createPinia())
app.use(router)
app.use(clerkPlugin, { publishableKey })

app.mount('#app')
