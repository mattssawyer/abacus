import axios, { type AxiosInstance } from 'axios'

export function createApiClient(): AxiosInstance {
  const baseURL = import.meta.env.VITE_API_BASE_URL

  if (!baseURL) {
    throw new Error('VITE_API_BASE_URL is not set')
  }

  return axios.create({
    baseURL,
    headers: {
      'Content-Type': 'application/json',
    },
  })
}

export const apiClient = createApiClient()
