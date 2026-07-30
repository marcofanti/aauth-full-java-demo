const BASE = import.meta.env['VITE_API_BASE_URL'] ?? 'http://portal.uma.lab:8000'

export interface StartResponse {
  requestId: string
  status: string
}

export interface Progress {
  requestId: string
  status: string
  interactionUrl: string | null
  interactionCode: string | null
  error: string | null
}

export interface Results {
  requestId: string
  status: string
  report: string
  completedAt: string
}

export interface Activity {
  timestamp: string
  agent: string
  message: string
}

export interface AgentInfo {
  id: string
  url: string
  status: string
}

async function asJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Backend returned HTTP ${response.status} for ${response.url}`)
  }
  return response.json() as Promise<T>
}

export const api = {
  start(customPrompt: string): Promise<StartResponse> {
    return fetch(`${BASE}/optimization/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        scenario: 'laptop_procurement',
        customPrompt: customPrompt || null,
        constraints: {},
      }),
    }).then((response) => asJson<StartResponse>(response))
  },

  progress(requestId: string): Promise<Progress> {
    return fetch(`${BASE}/optimization/progress/${requestId}`).then((response) => asJson<Progress>(response))
  },

  results(requestId: string): Promise<Results> {
    return fetch(`${BASE}/optimization/results/${requestId}`).then((response) => asJson<Results>(response))
  },

  activities(limit: number): Promise<Activity[]> {
    return fetch(`${BASE}/agents/activities?limit=${limit}`).then((response) => asJson<Activity[]>(response))
  },

  agents(): Promise<AgentInfo[]> {
    return fetch(`${BASE}/agents/status`).then((response) => asJson<AgentInfo[]>(response))
  },
}
