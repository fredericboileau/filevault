import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import Keycloak from 'keycloak-js'

export const keycloak = new Keycloak({
  url: 'http://localhost:8180',
  realm: 'filevault',
  clientId: 'filevault-app'
})

keycloak.init({ onLoad: 'login-required' }).then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
