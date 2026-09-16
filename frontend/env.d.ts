/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_PRIMEUI_LICENSE_KEY?: string
  readonly VITE_API_BASE_URL: string
  readonly VITE_CLERK_PUBLISHABLE_KEY: string
}

interface Window {
  Plaid: {
    create(config: {
      token: string
      onSuccess(publicToken: string, metadata: unknown): void
      onExit(error: unknown, metadata: unknown): void
    }): {
      open(): void
      destroy(): void
    }
  }
}