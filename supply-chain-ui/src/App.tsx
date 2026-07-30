import { AuthCallback } from './AuthCallback'
import { Dashboard } from './Dashboard'

/** Two views, no router dependency: the consent popup lands on /auth-callback. */
export function App() {
  if (window.location.pathname === '/auth-callback') {
    return <AuthCallback />
  }
  return <Dashboard />
}
