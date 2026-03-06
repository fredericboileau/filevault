import { keycloak } from './main'

const BASE_URL = 'http://localhost:8080'

const authFetch = (url: string, options?: RequestInit) =>
  fetch(BASE_URL + url, {
    ...options,
    headers: { Authorization: `Bearer ${keycloak.token}`, ...options?.headers }
  })

export default authFetch
