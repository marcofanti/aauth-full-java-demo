import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type Activity, type Progress } from './api'
import { CONSENT_COMPLETE_MESSAGE } from './AuthCallback'
import { ActivityFeed } from './components/ActivityFeed'
import { ConsentBanner } from './components/ConsentBanner'
import { ResultsPanel } from './components/ResultsPanel'

const POLL_INTERVAL_MS = 2000
const TERMINAL_STATUSES = new Set(['completed', 'failed'])

export function Dashboard() {
  const [prompt, setPrompt] = useState('')
  const [requestId, setRequestId] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [report, setReport] = useState<string | null>(null)
  const [consent, setConsent] = useState<{ url: string; code: string } | null>(null)
  const [activities, setActivities] = useState<Activity[]>([])
  const popupRef = useRef<Window | null>(null)
  const pollTimerRef = useRef<number | null>(null)

  const stopPolling = useCallback(() => {
    if (pollTimerRef.current !== null) {
      window.clearInterval(pollTimerRef.current)
      pollTimerRef.current = null
    }
  }, [])

  const clearConsent = useCallback(() => {
    setConsent(null)
    popupRef.current?.close()
    popupRef.current = null
  }, [])

  const handleProgress = useCallback(
    (progress: Progress) => {
      setStatus(progress.status)
      if (progress.status === 'interaction_required' && progress.interactionUrl && progress.interactionCode) {
        setConsent({ url: progress.interactionUrl, code: progress.interactionCode })
        if (!popupRef.current || popupRef.current.closed) {
          const callback = `${window.location.origin}/auth-callback?requestId=${progress.requestId}`
          const separator = progress.interactionUrl.includes('?') ? '&' : '?'
          popupRef.current = window.open(
            `${progress.interactionUrl}${separator}callback=${encodeURIComponent(callback)}`,
            'aauth-consent',
            'width=520,height=620',
          )
        }
        return
      }
      clearConsent()
      if (progress.status === 'completed') {
        stopPolling()
        api.results(progress.requestId)
          .then((results) => setReport(results.report))
          .catch((cause: unknown) => setError(String(cause)))
      } else if (progress.status === 'failed') {
        stopPolling()
        setError(progress.error ?? 'Optimization failed')
      }
    },
    [clearConsent, stopPolling],
  )

  const startOptimization = useCallback(() => {
    stopPolling()
    clearConsent()
    setError(null)
    setReport(null)
    setStatus('starting')
    api.start(prompt.trim())
      .then((started) => {
        setRequestId(started.requestId)
        setStatus(started.status)
        pollTimerRef.current = window.setInterval(() => {
          api.progress(started.requestId).then(handleProgress).catch((cause: unknown) => setError(String(cause)))
          api.activities(20).then(setActivities).catch(() => undefined)
        }, POLL_INTERVAL_MS)
      })
      .catch((cause: unknown) => {
        setStatus('failed')
        setError(String(cause))
      })
  }, [clearConsent, handleProgress, prompt, stopPolling])

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (
        event.origin === window.location.origin &&
        (event.data as { type?: string } | null)?.type === CONSENT_COMPLETE_MESSAGE
      ) {
        clearConsent()
      }
    }
    window.addEventListener('message', onMessage)
    return () => {
      window.removeEventListener('message', onMessage)
      stopPolling()
    }
  }, [clearConsent, stopPolling])

  useEffect(() => {
    api.activities(20).then(setActivities).catch(() => undefined)
  }, [])

  const running = status !== null && !TERMINAL_STATUSES.has(status)

  return (
    <main className="dashboard">
      <header>
        <h1>AcmeCorp Supply Chain</h1>
        <p className="subtitle">Multi-agent optimization demo with AAuth-signed agent-to-agent calls</p>
      </header>

      {consent && <ConsentBanner code={consent.code} url={consent.url} />}

      <section className="controls">
        <textarea
          placeholder='Optional custom prompt — include "perform market analysis" to trigger the second agent hop'
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          rows={3}
        />
        <button type="button" onClick={startOptimization} disabled={running}>
          {prompt.trim() ? 'Run Custom Optimization' : 'Optimize Laptop Supply Chain'}
        </button>
        {status && (
          <p className="status">
            Status: <strong>{status}</strong>
            {requestId && <span className="request-id"> · {requestId}</span>}
          </p>
        )}
        {error && <p className="error">{error}</p>}
      </section>

      <div className="columns">
        <ActivityFeed activities={activities} />
        <ResultsPanel report={report} />
      </div>
    </main>
  )
}
