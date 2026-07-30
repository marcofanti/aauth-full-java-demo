import { useEffect } from 'react'

export const CONSENT_COMPLETE_MESSAGE = 'aauth-consent-complete'

/**
 * Loaded only inside the consent popup: the Person Server redirects here after the user
 * approves. Notifies the opener (the dashboard) and asks the user to close the window.
 */
export function AuthCallback() {
  useEffect(() => {
    window.opener?.postMessage({ type: CONSENT_COMPLETE_MESSAGE }, window.location.origin)
  }, [])

  return (
    <main className="auth-callback">
      <h1>Authorization complete</h1>
      <p>You can close this window and return to the dashboard.</p>
    </main>
  )
}
