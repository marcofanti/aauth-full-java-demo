# User-consent flow (consent mode)

The two-channel design: the backend's optimization run blocks on Person Server polling
while the UI independently polls backend progress — that is how a headless agent chain
surfaces a human decision.

```
UI                    backend                 supply-chain-agent        Person Server
│  POST /optimization/start                                                  │
│──────────────────►│ (returns requestId immediately; work continues         │
│                   │  on a background virtual thread)                       │
│  poll /progress   │  A2A message/send (scheme=jwt, aa-agent+jwt)           │
│◄─────────────────►│──────────────────────►│ 401 AAuth-Requirement          │
│                   │◄──────────────────────│ (resource token,               │
│                   │                       │  scope "... require:user")     │
│                   │  POST /token (resource token) ────────────────────────►│
│                   │◄─ 202 + pending URL + interaction url/code ────────────│
│                   │  onInteraction(url, code):                             │
│                   │    progress := interaction_required                    │
│  banner + popup   │  … polls pending URL (signed GETs) …                   │
│◄──────────────────│                                                        │
│  user approves in /ui/consent.html  ──────────────────────────────────────►│
│  (or REST: GET /consent?code=… → POST /consent/{id}/decision)              │
│                   │◄─ 200 + aa-auth+jwt ───────────────────────────────────│
│                   │  retry A2A with scheme=jwt (auth token)                │
│                   │──────────────────────►│ verified, scopes OK            │
│  progress: completed; results fetched and rendered                         │
```

Key points:

- The consent popup URL is the `interaction_url` from the Person Server's 202 plus a
  `callback=<ui>/auth-callback?requestId=…` parameter appended by the UI. After
  approval the popup lands on `/auth-callback`, which `postMessage`s the opener so the
  dashboard closes it. If the popup is blocked, the banner's fallback link works too —
  the backend's pending-URL polling completes regardless of what the popup does.
- Denial ends the run: the poller returns `denied` and the backend marks the request
  `failed` ("request was denied").
- The supply-chain agent's own hop to the market-analysis agent runs `auth-token` (no
  `require:user`) in the standard `consent` run mode, so only one human approval is
  needed. If that hop is switched to consent too, its interaction URL/code appear in
  the supply-chain agent's log for approval via the Person Server UI (`/ui`, bearer
  `mytoken`) — it has no channel to the browser.
- Approval can be driven headlessly for tests:
  `GET {ps}/consent?code=<interaction_code>` → `pending_id`, then
  `POST {ps}/consent/{pending_id}/decision` with `{"approved": true|false}`.
