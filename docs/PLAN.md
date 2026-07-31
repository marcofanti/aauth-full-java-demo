# aauth-full-java-demo — Implementation Plan

> The plan as approved before implementation; kept as-is for the record. Current phase
> status and every deviation are tracked in [PROGRESS.md](PROGRESS.md).

Java reimplementation of the ideas in `../aauth-full-demo` (Python). That repo has no
license, so this is a clean-room build from its *architecture and protocol flows only* —
no code, prose, or config is copied. The AAuth protocol logic comes from
`../aauth-java-library` (`io.github.marcofanti:aauth` / `aauth-signing`,
`0.1.0-SNAPSHOT`, MIT) once that library is finished.

## What the demo demonstrates

A three-hop agent chain where every agent-to-agent HTTP request is signed with
RFC 9421 HTTP Message Signatures and authorized at the edge by an AAuth-aware gateway:

```
Browser (React UI portal.uma.lab:3050, no login)
   │ plain REST
   ▼
backend (portal.uma.lab:8000)  ──A2A JSON-RPC, AAuth-signed──►  agentgateway (gateway.uma.lab:3000)
                                                     │  gRPC ExtAuthz → aauth-service (:7070/:8081)
                                                     ▼
                                              supply-chain-agent (:9999)
                                                     │  A2A, AAuth-signed (via same gateway)
                                                     ▼
                                              market-analysis-agent (:9998)

ps.uma.lab (:8765)     — external repo (aauth-person-server): Agent Provider,
                         Person Server (token exchange), consent UI
grafana.uma.lab        — lab observability stack (phase 7 traces land here or in Jaeger)
```

Hostnames come from the uma.lab environment (`/etc/hosts` → 127.0.0.1 locally):
`portal.uma.lab` = UI + backend API, `gateway.uma.lab` = both agents (direct ports 9999/9998
in mode0; the agentgateway takes over this host in phase 2 and routes to the agents, with a
resource ID per route), `ps.uma.lab` = Person Server. `keycloak.uma.lab` and
`alice-as.uma.lab` exist in the lab but are unused by this demo.

Three runnable modes, selected by which gateway/aauth-service config pair is loaded:

| Mode | Enforcement | Flow |
|---|---|---|
| `mode1` | Identity only | `aa-agent+jwt` in `Signature-Key` is sufficient |
| `mode3` | Auth token required | Gateway 401s with a resource token; caller exchanges it at the Person Server for an `aa-auth+jwt` and retries |
| `user-consent` | Auth token + `require:user` scope | Person Server defers (202 + pending URL); human approves in a browser popup; caller polls to completion |

Key architectural facts carried over from the reference (its README is stale — these
reflect its current code):

- **Agents never verify inbound signatures.** Verification is entirely at the
  gateway (ExtAuthz). Agents sign *outbound* requests and trust gateway-injected
  identity headers. The demo therefore needs only the *client/agent* side of the
  Java library — not the resource-side verifier.
- **All A2A/MCP traffic uses `scheme=jwt`** (`Signature-Key: sig=jwt;jwt="<token>"`).
  `hwk` is used only for Agent Provider registration; `jkt-jwt` only for token refresh.
- **Two-key model per service.** A stable Ed25519 key persisted to disk anchors
  identity; an ephemeral per-process Ed25519 key does the actual HTTP signing and is
  bound into the agent token via `cnf.jwk`.
- **No LLM.** All "intelligence" is keyword matching on the request text, hardcoded
  business data, deterministic arithmetic, and Markdown templating. The demo's value
  is the auth plumbing, so the business logic stays deliberately trivial.
- **No Docker.** AAuth signs `@authority`; container DNS breaks the signed hostnames.
  Everything runs as native local processes with `/etc/hosts` entries for the uma.lab
  hostnames (`portal.uma.lab`, `gateway.uma.lab`, `ps.uma.lab`).

## Tech stack

- **Java 26, Maven multi-module** — per project decision (2026-07-30). The library still
  targets `--release 17`; its jars are consumable from 26. Virtual threads back the
  backend's blocking optimization runs.
- **Spring Boot 4.x** for the backend REST API and both agent servers. The library is
  framework-free by design; the demo owns the Spring wiring (client interceptors,
  `@Async` background jobs, config properties).
- **A2A layer: hand-rolled thin JSON-RPC.** The demo uses a tiny slice of A2A 0.3.0 —
  non-streaming `message/send` plus `GET /.well-known/agent-card.json` — and signing
  requires byte-identical control of the serialized request body (the Python demo
  fought its SDK over exactly this). A minimal internal `a2a-support` module (JSON-RPC
  envelope records, agent-card records, Jackson with deterministic serialization)
  avoids that fight. Revisit `a2aproject/a2a-java` later only if interop with foreign
  A2A clients becomes a goal.
- **React + Vite** UI on port 3050 (fresh code; same UX ideas: one dashboard,
  optimize button, custom-prompt textarea, activity feed, Markdown results, consent
  popup + banner).
- **External infra reused as-is (run, not copied):** `aauth-person-server` (Python),
  `agentgateway` binary, `aauth-service` binary (`extauth-aauth-resource`), Jaeger
  all-in-one. The demo writes its *own* gateway/aauth-service YAML configs and
  generates its own Ed25519 resource keys.

## Module layout

```
aauth-full-java-demo/
├── pom.xml                     # parent: io.github.marcofanti:aauth-demo-parent
├── a2a-support/                # JSON-RPC + agent-card records, deterministic Jackson
├── demo-common/                # AAuth bootstrap client, signing interceptor,
│                               # challenge/exchange handling, key persistence, OTel helpers
│                               # (thin demo-side wrappers around the aauth library)
├── backend/                    # Spring Boot :8000 — public REST API + AAuth client
├── supply-chain-agent/         # Spring Boot :9999 — A2A server + AAuth client (2nd hop)
├── market-analysis-agent/      # Spring Boot :9998 — A2A server, leaf
├── supply-chain-ui/            # React + Vite :3050
├── gateway/                    # agentgateway + aauth-service configs (3 mode pairs),
│                               # resource key generation script
├── scripts/                    # start-infra.sh <mode>, stop-infra.sh, run-tests.sh
├── integration-tests/          # JUnit 5 end-to-end suites, mode-tagged
└── docs/                       # PLAN.md, PROGRESS.md, MODES.md, CONSENT_FLOW.md
```

## Component specs

### backend (:8000)

Public, unauthenticated REST API for the UI; first AAuth agent in the chain.

Endpoints: `GET /health`, `GET /auth/me` (static guest), `GET /agents/status`,
`GET|DELETE /agents/activities`, `POST /optimization/start`,
`GET /optimization/progress/{id}`, `GET /optimization/results/{id}`,
`GET /optimization/all`, `DELETE /optimization/clear`.

The load-bearing design point is the **two-channel consent pattern**:
`POST /optimization/start` returns `{requestId, status}` immediately and runs the
A2A call in a background task (Spring `@Async` over a dedicated executor). When the
Person Server defers for consent, the background task blocks polling the pending URL
while the *progress* record flips to `INTERACTION_REQUIRED` carrying
`interactionUrl` + `interactionCode`; the UI's 2 s poll of `/progress/{id}` surfaces
them. Status enum: `PENDING`, `INTERACTION_REQUIRED`, `APPROVAL_PENDING`,
`AUTHORIZING`, `RUNNING`, `COMPLETED`, `FAILED`.

AAuth client behavior (all via `demo-common` + the library):
1. Bootstrap at startup (see Flow A below).
2. Sign the A2A `message/send` POST to the supply-chain agent with `scheme=jwt`
   carrying the current `aa-agent+jwt`.
3. On 401: parse `AAuth-Requirement`, extract the resource token, run the
   exchange against the Person Server (with `on_interaction` callback wired to the
   progress record), rebuild the signer with the returned `aa-auth+jwt`, retry once.

### supply-chain-agent (:9999)

A2A server (JSON-RPC `POST /`, `GET /.well-known/agent-card.json`) and mid-chain
AAuth client. Business logic: keyword-match the message text (laptop/hardware →
focus area; cost/budget → cost goal; inventory/stock → buffer policy), apply
hardcoded business policies (inventory buffer months, approval thresholds, vendor
tiers, per-model min stock), and render a Markdown report. If the text contains
`"perform market analysis"`, make a signed downstream A2A call to the
market-analysis agent (its own bootstrap identity, its own 401→exchange handling —
an independent exchange, no nested `act` chaining) and splice the result in as a
`## Market Analysis` section. Downstream failure is non-fatal.

### market-analysis-agent (:9998)

Leaf A2A server. Keyword-routes into one of four canned analyses (laptop demand /
trend forecast / demand patterns / comprehensive), scales hardcoded inventory,
hiring, and growth data by the requested timeframe, and returns a Markdown report.
The reference's vestigial MCP tool-discovery call is **out of scope** (revisit only
if an MCP leg becomes interesting).

### supply-chain-ui (:3050)

Single dashboard: optimize button, custom-prompt textarea, activity feed, Markdown
results panel. Polls `/optimization/progress/{id}` every 2 s. On
`INTERACTION_REQUIRED`: show an inline banner with the interaction code and open a
popup on the Person Server consent page with `&callback=<ui>/auth-callback?requestId=…`
appended; keep polling. `/auth-callback` posts `aauth-consent-complete` to the opener,
which closes the popup and clears the banner. Backend polling completes independently.
Only config: `VITE_API_BASE_URL`.

### gateway/ and scripts/

Three config pairs (`mode1`, `mode3`, `user-consent`) on `gateway.uma.lab`, mapping
each agent route (path- or port-based, since both agents share the gateway host) to a
resource ID, with CEL authorization rules per mode (identity suffix check for mode1;
`act.sub == agent && scope` checks for mode3/consent). A keygen script produces the
two Ed25519 resource keys. `start-infra.sh <mode>` boots Person Server → gateway +
aauth-service → the three Java services (health-checked, PIDs recorded, logs to
`logs/`); `stop-infra.sh` tears down; `run-tests.sh [mode|all]` cycles
start → test → stop per mode.

## AAuth flows (contract with the library)

**Flow A — bootstrap (every service at startup):** fetch
`{AGENT_SERVER_BASE}/.well-known/aauth-agent.json` (retry w/ backoff) → load-or-create
stable Ed25519 key on disk, generate ephemeral key → `POST /register`
(`{stable_pub, agent_name}`, signed with ephemeral key, `scheme=hwk`) → on 202 poll
the `Location` URL until approved in the Person Server UI → hold `aa-agent+jwt`
(`cnf.jwk` = ephemeral pub) → refresh before expiry: new ephemeral pair, stable key
signs a short-lived delegation JWT, `POST /refresh` signed `scheme=jkt-jwt`.

**Flow B — mode1:** sign with `scheme=jwt` + agent token → gateway verifies
proof-of-possession against `cnf.jwk` → allow. No 401 path.

**Flow C — mode3:** gateway 401s with
`AAuth-Requirement: requirement=auth-token, resource-token="<aa-resource+jwt>"` →
extract resource token → signed `POST {PS}/token` with `{"resource_token": …}` →
200 returns `aa-auth+jwt` (sender-constrained to the caller's ephemeral key, flat
`act`) → retry with the auth token in `Signature-Key`.

**Flow D — user-consent:** same until the PS returns `202` +
`Location: /pending/{id}` + `AAuth-Requirement: requirement=interaction; url=…; code=…`
→ `on_interaction(url, code)` surfaces both to the UI → library poller does signed
GETs on the pending URL (`202` pending/interacting, `200` token, `403` denied,
`408` timeout, `410` code consumed) → retry with token.

Library APIs the demo consumes (per the library's PLAN.md naming; final signatures
may drift — `demo-common` is the single adaptation layer): keypair/JWK/thumbprint
utilities, `AgentRequestSigner` (schemes `hwk`, `jkt-jwt`, `jwt`), challenge parsing +
resource-token extraction, the token-exchange helper with `on_interaction` callback,
pending-URL poller, metadata fetchers, token-claim parsing. **Not needed:**
`RequestVerifier` / `ChallengeBuilder` / `ResourceTokenIssuer` (library phase 10) —
the gateway plays the resource role.

## Known gotchas (from the reference implementation)

1. **Signed bytes must equal wire bytes.** Serialize the JSON-RPC body once with a
   deterministic Jackson config and hand those exact bytes to both the signer and the
   HTTP client. Never let the client re-serialize.
2. **Normalize an empty path to `/`** before signing; a trailing slash on the
   configured downstream URL is significant (`@path` mismatch → 401).
3. **Canonical authority comes from the configured `*_AGENT_ID_URL`** (SPEC §10.3.1),
   never from the `Host` header or the connection URL.
4. **Strip any pre-existing `Content-Digest`** before signing; **inject `traceparent`
   before signing** so it is covered by the signature.
5. **The 401 arrives wrapped.** Whatever HTTP/A2A client wrapper is used must expose
   response status + headers on error so `AAuth-Requirement` can be parsed.
6. `/etc/hosts` must map `portal.uma.lab`, `gateway.uma.lab` and
   `ps.uma.lab` to `127.0.0.1` (documented in README; checked by
   `start-infra.sh`).
7. Ed25519 is native in Java 17 (`java.security` EdDSA); Nimbus handles JWK
   round-tripping and RFC 7638 thumbprints. No BouncyCastle needed.

## Phases

Ordered so that everything except AAuth is buildable **now**, while the library is
still in progress. Library phases required before demo phases 4–7: library 2–9
(signing, tokens, headers, metadata, agent role). Library phases 10–11 are not
prerequisites.

- **Phase 0 — Scaffold.** Parent POM mirroring the library's quality gates
  (JDK 17 `-Werror`, Spotless/Palantir, JaCoCo, enforcer), empty modules, React app
  skeleton, README, `/etc/hosts` doc, this plan + PROGRESS.md. *No dependencies.*
- **Phase 1 — Business core, no auth ("mode0").** `a2a-support` (JSON-RPC records,
  agent cards, deterministic serializer — unit-test byte-stability), both agents'
  canned logic + Markdown reports, backend orchestration incl. async start/progress/
  results state machine, direct calls backend→SCA→MAA on 8000/9999/9998 bypassing the
  gateway. UI dashboard end-to-end. *Fully testable standalone.*
- **Phase 2 — Infra.** Person Server checkout + run script, agentgateway +
  aauth-service download/pinning, the three config pairs, resource keygen,
  `start-infra.sh`/`stop-infra.sh`, route mode0 traffic through the gateway with
  enforcement off to validate host-based routing.
- **Phase 3 — Library integration gate.** `mvn install` the finished
  `aauth-java-library`; write `demo-common`'s adaptation layer against its real API;
  spike test: sign a request and get it past aauth-service in mode1 config.
  *Blocked on library phases 2–9.*
- **Phase 4 — Bootstrap + Mode 1.** Two-key persistence, register/poll/refresh
  lifecycle in all three services, signing interceptor on all outbound A2A calls,
  mode1 green end-to-end through the gateway.
- **Phase 5 — Mode 3.** 401 unwrapping, resource-token extraction, PS exchange,
  signer rebuild + single retry, independent SCA→MAA exchange. mode3 green.
- **Phase 6 — User consent.** `on_interaction` → progress record → UI banner +
  popup + `/auth-callback` postMessage; pending-URL polling to terminal states
  (approved / denied / timeout). user-consent mode green.
- **Phase 7 — Observability.** OTel Java SDK in all three services (service names
  `supply-chain-backend`, `supply-chain-agent`, `market-analysis-agent`), OTLP gRPC
  to Jaeger :4317, W3C propagation with traceparent injected pre-signing, span helpers
  in `demo-common`, gateway trace enrichment config, Jaeger walkthrough doc.
- **Phase 8 — Integration tests + docs.** JUnit suites tagged `mode1` / `mode3` /
  `user-consent` / `health` mirroring the reference's scenarios: happy path, market-
  analysis hop, concurrent requests, mode1-never-asks-consent assertion, consent
  approval driven via the PS REST API (`GET /consent?code=…` →
  `POST /consent/{pendingId}/decision`), consent denial, missing-approval timeout.
  `run-tests.sh all` cycles all modes. Final README, MODES.md, CONSENT_FLOW.md.

## Out of scope

- Resource-side verification in Java (gateway owns it; library phase 10 unused).
- Nested `act`-chain token exchange (SPEC §9.10) — the reference deleted it; the
  SCA→MAA hop uses an independent exchange.
- MCP tool discovery from the market-analysis agent.
- Streaming A2A, push notifications, LLM-backed logic, Docker packaging, human login
  on the UI.
