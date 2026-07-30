# Progress

Phase status for the plan in [PLAN.md](PLAN.md). Update on phase completion or when a
design decision deviates from the plan or from the Python reference.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Scaffold (POMs, quality gates, UI skeleton, docs) | **done** (2026-07-30) |
| 1 | Business core without auth (a2a-support, agents, backend, UI) | **done** (2026-07-30) — `mvn clean verify` green (44 tests, 4×80% coverage gates), UI click-through and 3-service curl flow verified live |
| 2 | Infra (Person Server, gateway + aauth-service configs, scripts) | **partial** (2026-07-30) — local `aauth-person-server` runs via `run-person-server.sh` (uma.lab origins, unmodified repo); agentgateway/aauth-service binaries still not set up |
| 3 | Library integration gate (`demo-common` adaptation layer, signed spike) | **done** (2026-07-30) — library finished (11/11 phases) and installed; `demo-common` wraps it; both A2A hops HWK-signed and verified in-process, live-tested (signed chain completes, unsigned → 401 + `Accept-Signature`) |
| 4 | Bootstrap + identity mode (`scheme=jwt`) | **done** (2026-07-30) — stable+ephemeral keys, register (`hwk`) → 202 → approval (auto via `/person` API in `run-demo.sh`) → `aa-agent+jwt`; both hops signed `scheme=jwt`, verifiers require identity (JWKS discovery from PS); restart re-registers with no approval (stable-key 200 path); live-tested end to end. Token refresh (`jkt-jwt`) not yet implemented |
| 5 | Mode 3 (401 → resource token → PS exchange) | **done** (2026-07-30) — agents serve `/.well-known/aauth-resource.json` + JWKS with persistent resource keys; identified callers without scopes get an `AAuth-Requirement` challenge embedding a resource token; `A2aAuthClient` exchanges it at the PS and retries with the `aa-auth+jwt` (cached per process). Live-tested: both hops exchanged autonomously, scopes verified (`supply-chain:optimize`, `market-analysis:analyze`) |
| 6 | User-consent flow | **done** (2026-07-30) — `consent` mode appends `require:user` to the SCA's resource-token scope; PS defers (202), backend's `onInteraction` flips the record to `interaction_required` with URL + code, UI shows banner + popup. Live-tested three ways: REST-driven approval, REST-driven **denial** (fails with "request was denied"), and full browser click-through of the PS consent page → dashboard completes |
| 7 | Observability (OTel → Jaeger) | **done** (2026-07-30) — OpenTelemetry Java agent 2.30.0 (zero-code) attached by `run-demo.sh` when `tools/` is populated via `setup-tracing.sh`; Jaeger v2.20.0 native binary (`run-jaeger.sh`, UI :16686). Verified: one distributed trace spans backend → supply-chain-agent → market-analysis-agent (35 spans) with the 401/exchange/consent choreography visible. `traceparent` is not signature-covered, so agent-injected headers compose cleanly with AAuth signing |
| 8 | Integration tests + docs | **done** (2026-07-30) — `integration-tests` module (skipped in normal builds) with tag groups `core`/`signed`/`ps`/`consent`; `run-tests.sh [mode|all]` cycles start → test → stop per mode. Full matrix green live: off 5, hwk 8, jwt 10, auth-token 10, consent 8 tests (incl. consent approval, denial, cached-token reuse). MODES.md + CONSENT_FLOW.md written |

## Decision log

- 2026-07-30 — Plan created. Clean-room reimplementation of `../aauth-full-demo`
  (unlicensed): architecture/flows only, no code or config copied.
- 2026-07-30 — Hand-rolled `a2a-support` (minimal JSON-RPC + agent cards) instead of
  an A2A SDK, to keep byte-level control of signed request bodies.
- 2026-07-30 — Resource-side verification stays at the gateway; library phase 10
  (`RequestVerifier` et al.) is not a demo dependency.
- 2026-07-30 — **Java 26 target** (user request), up from the library's 17. Enforcer
  requires `[26,)`; backend uses virtual threads for optimization runs.
- 2026-07-30 — Spring Boot 4.1.0 (`spring-boot-starter-webmvc`; Boot 4 defaults to
  Jackson 3). `a2a-support` deliberately uses **Jackson 2.19** (`com.fasterxml`), the
  same pin as aauth-java-library, and owns the A2A wire bytes end-to-end; Spring's
  Jackson 3 only serializes the backend's own REST DTOs.
- 2026-07-30 — `-parameters` compiler flag required: Spring Framework 7 no longer
  resolves `@PathVariable`/`@RequestParam` names from debug info.
- 2026-07-30 — `demo-common` module deferred to phase 3: creating it now would hold
  only placeholders. The AAuth seam in the meantime is `RequestSigner` in `a2a-support`.
- 2026-07-30 — UI: Vite 8 + React 19 + TypeScript 7 (strict flags per global standards),
  no router (path check for `/auth-callback`), no CSS framework. oxlint clean.
- 2026-07-30 — **uma.lab hostnames** (user request, replacing localhost):
  `portal.uma.lab` = UI (:3050) + backend API (:8000); `gateway.uma.lab` = both agents
  (:9999/:9998 direct in mode0 — the agentgateway takes this host over in phase 2, so
  agent canonical authorities stay stable); `ps.uma.lab` = Person Server (phase 2+);
  `grafana.uma.lab` = observability (phase 7). Both agents sharing one gateway host
  means gateway routing will be path- or port-based, not Host-based as in the Python
  reference. `keycloak.uma.lab` / `alice-as.uma.lab` unused.

- 2026-07-30 — **Phase 3 landed as "HWK-signed mode0"**, going slightly beyond the plan:
  since the gateway (phase 2) isn't up yet, the agents verify inbound signatures
  in-process via the library's `RequestVerifier` (toggle: `demo.aauth.mode=off|hwk`,
  default `hwk`). When the agentgateway lands, edge verification can replace or
  complement this.
- 2026-07-30 — **RFC 9530 digest enforcement lives in `demo-common`**: both the Java
  library and the Python reference sign/verify over the `Content-Digest` *header* but
  never recompute the digest from the body — a tampered body with an intact header
  passes `verify_signature` in both. `AAuthInboundVerifier` recomputes and compares
  (`SignatureBase.contentDigest`). Worth considering as a library improvement.

- 2026-07-30 — **Phase 4 design notes**: the market-analysis agent does not register with
  the Person Server (it makes no outbound calls; identity would be unused). Mode is
  per-service `demo.aauth.mode=off|hwk|jwt` (default `jwt` in properties). The PS's
  strict h11 parser rejects duplicate headers — bootstrap merges header maps before
  sending and pins HTTP/1.1. `run-demo.sh jwt` auto-approves pending registrations via
  the PS `/person` REST API (bearer `mytoken`).

- 2026-07-30 — **Phases 5–6 design notes**:
  - Run modes map per service in `run-demo.sh`: `auth-token` = backend jwt / both agents
    auth-token; `consent` = backend jwt / SCA consent / MAA auth-token (only the
    user-facing hop demands consent; the internal SCA→MAA hop exchanges autonomously,
    and an SCA-side consent demand is logged for PS-UI approval since it has no UI
    channel).
  - **Resource keys must be persistent** (now via `StableKeys`): the PS caches resource
    JWKS per issuer (300 s in-memory), so per-process resource keys break verification
    after an agent restart.
  - **Library candidate fix**: `TokenExchange`'s default `HttpClient` attempts the h2c
    upgrade, which uvicorn/h11 mishandles (empty body at FastAPI). The demo injects an
    HTTP/1.1-pinned client via `Exchange.builder().httpClient(...)`; the library default
    should probably pin HTTP/1.1 too.
  - `run-person-server.sh` uses `exec` in the subshell so the recorded PID is uvicorn
    itself — without it, "stopping" the PS orphaned the server and later starts silently
    failed to bind while health checks passed against the stale process.
  - Auth tokens are cached per client process: after one consent approval, subsequent
    calls reuse the token until expiry — a second run does not re-prompt (correct AAuth
    semantics; restart the backend to force a fresh consent).

- 2026-07-30 — **Phase 8 notes**: integration tests use surefire (not failsafe) with an
  explicit `**/*IT.java` include — the `IT` suffix is outside surefire's defaults and
  the first run silently matched zero tests. The uvicorn h2c issue struck a third time
  (test client dropping POST bodies to the PS); every JDK `HttpClient` that talks to
  the Person Server must pin HTTP/1.1. Consent tests are ordered denial-first because
  an approval caches the auth token and later runs stop prompting.

## Deviations from the Python reference

- Reports, business-policy values, and market data are original content (different
  numbers/wording than the reference) — only the flow shapes carry over.
- Backend service name is `aauth-demo-backend` (reference used `supply-chain-api`).
- `/optimization/results/{id}` returns 409 while the run is still in progress
  (reference returned a partially-populated results object).
