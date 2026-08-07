# Progress

Phase status for the plan in [PLAN.md](PLAN.md). Update on phase completion or when a
design decision deviates from the plan or from the Python reference.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Scaffold (POMs, quality gates, UI skeleton, docs) | **done** (2026-07-30) |
| 1 | Business core without auth (a2a-support, agents, backend, UI) | **done** (2026-07-30) — `mvn clean verify` green (44 tests, 4×80% coverage gates), UI click-through and 3-service curl flow verified live |
| 2 | Infra (Person Server, gateway + aauth-service edge) | **done** (2026-07-31) — agentgateway v0.11.4 + aauth-service v0.0.1 (christian-posta releases, pinned in `setup-gateway.sh`) own gateway.uma.lab:9999/:9998 with agents behind them on 29999/29998; three policy variants (`gateway/aauth-config-*.yaml`). All three edge run modes live-tested: `edge` (identity, both hops `allowed` with delegates), `edge-auth` (`level: authorized` after autonomous exchange against edge-issued resource tokens), `edge-consent` (deferred, approved, completed) |
| 3 | Library integration gate (`demo-common` adaptation layer, signed spike) | **done** (2026-07-30) — library finished (11/11 phases) and installed; `demo-common` wraps it; both A2A hops HWK-signed and verified in-process, live-tested (signed chain completes, unsigned → 401 + `Accept-Signature`) |
| 4 | Bootstrap + identity mode (`scheme=jwt`) | **done** (2026-07-30) — stable+ephemeral keys, register (`hwk`) → 202 → approval (auto via `/person` API in `run-demo.sh`) → `aa-agent+jwt`; both hops signed `scheme=jwt`, verifiers require identity (JWKS discovery from PS); restart re-registers with no approval (stable-key 200 path); live-tested end to end. **Token refresh implemented** (2026-07-30): `ManagedIdentity` renews the `aa-agent+jwt` before expiry via `jkt-jwt` (stable key signs a `jkt-s256+jwt` delegation binding a fresh ephemeral key); `A2aAuthClient` drops cached auth tokens on rotation. Live-tested with 90s tokens (`AAUTH_AS_AGENT_TOKEN_LIFETIME=90`): both services refreshed on schedule and the post-refresh chain re-exchanged and completed |
| 5 | Mode 3 (401 → resource token → PS exchange) | **done** (2026-07-30) — agents serve `/.well-known/aauth-resource.json` + JWKS with persistent resource keys; identified callers without scopes get an `AAuth-Requirement` challenge embedding a resource token; `A2aAuthClient` exchanges it at the PS and retries with the `aa-auth+jwt` (cached per process). Live-tested: both hops exchanged autonomously, scopes verified (`supply-chain:optimize`, `market-analysis:analyze`) |
| 6 | User-consent flow | **done** (2026-07-30) — `consent` mode appends `require:user` to the SCA's resource-token scope; PS defers (202), backend's `onInteraction` flips the record to `interaction_required` with URL + code, UI shows banner + popup. Live-tested three ways: REST-driven approval, REST-driven **denial** (fails with "request was denied"), and full browser click-through of the PS consent page → dashboard completes |
| 7 | Observability (OTel → Jaeger) | **done** (2026-07-30) — OpenTelemetry Java agent 2.30.0 (zero-code) attached by `run-demo.sh` when `tools/` is populated via `setup-tracing.sh`; Jaeger v2.20.0 native binary (`run-jaeger.sh`, UI :16686). Verified: one distributed trace spans backend → supply-chain-agent → market-analysis-agent (35 spans) with the 401/exchange/consent choreography visible. `traceparent` is not signature-covered, so agent-injected headers compose cleanly with AAuth signing |
| 8 | Integration tests + docs | **done** (2026-07-30) — `integration-tests` module (skipped in normal builds) with tag groups `core`/`signed`/`ps`/`consent`; `run-tests.sh [mode|all]` cycles start → test → stop per mode. Full matrix green live: off 5, hwk 8, jwt 10, auth-token 10, consent 8 tests (incl. consent approval, denial, cached-token reuse). MODES.md + CONSENT_FLOW.md written |

## Decision log

- 2026-08-07 — **Adopt aauth-java-library 0.2.3 (AAuth draft-10)**: 0.2.x signs with
  fully-specified `alg: Ed25519` (RFC 9864) and adds the agent-token `ps` claim. The
  first bump (0.2.1) broke both CI matrix legs — the matrix doing its job:
  (java leg) 0.2.1's `ps`-claim check rejected plain-http dev origins, fixed upstream
  in 0.2.2; (python leg) the Python PS's `EdDSA` JWKs were rejected by draft-10
  verification, fixed upstream in 0.2.3 (legacy-`EdDSA` tolerance). The reverse
  direction needed a demo-side fix: the Python library's `verify_resource_token`
  allows only `EdDSA` and 500s on draft-10 `Ed25519` tokens, so
  `scripts/portal_hotfixes.py` (renamed from `portal_permission_hotfix.py`, now two
  fixes) registers `Ed25519` with PyJWT and widens the allowlist at runtime.
  Verified locally: 6/6 modes vs the Java PS (main, pinned 0.2.3) and 6/6 vs the
  Python PS. Edge modes and the Docker default variant remain incompatible with
  0.2.x — the Go verifier (extauth-aauth-resource v0.0.1, no commits since May)
  predates draft-10; needs an upstream update or a pre-draft-10 pin.

- 2026-08-02 — **Missions mode** (post-plan): `run-demo.sh missions` = `jwt` identity
  plus the Person Server's mission layer. Backend proposes a mission (approved tools:
  `supply-chain:optimize`, `market-analysis:analyze`), gates each step via signed
  `POST /permission`, audits results into the mission log, and closes with an
  out-of-scope `inventory:purchase` that defers to the user (`interaction_required`).
  New: `MissionClient`/`MissionException` (demo-common), `mission` package + REST API
  (backend), `MissionFlowIT` (tag `missions`, in `run-tests.sh all`). Live-verified:
  deny and approve paths, full PS mission log. Two upstream gaps found (Python PS):
  deferred mission proposals aren't agent-pollable (`GET /pending` 404s on mission
  pendings) — so mission creation stays auto-approved; and the portal app 500s on
  deferred permission checks — fixed at runtime by `scripts/portal_hotfixes.py`
  (wraps the app, no upstream edits). Both worth reporting upstream. Java PS has no
  mission endpoints; missions mode requires the Python PS. See docs/MISSIONS.md.

- 2026-08-02 — **Hostnames in hosts.env** (user request): all uma.lab names live in
  `hosts.env` at the repo root (env-style, every value `${VAR:-default}`-overridable;
  includes the reserved grafana/keycloak/alice-as names). Scripts source it;
  run-demo.sh passes `--demo.*` flags to the services; run-tests.sh passes
  `-Ddemo.*.host` system properties read by `DemoApi`. Java property defaults keep the
  same names for manual runs. Gateway YAMLs and docker-compose intentionally keep
  literals (signed authorities and compose aliases are coupled to them).

- 2026-08-02 — **CI workflows**: `.github/workflows/build.yml` (mvn verify, Java 26,
  library from Central) and `integration.yml` (checks out the public Python PS as a
  sibling, maps uma.lab names to loopback, runs `run-tests.sh all`; uploads logs on
  failure). Actions pinned to SHAs; actionlint + zizmor clean. Edge modes stay
  local-only (gateway binaries). The Java PS is not in CI — it has no public remote.

- 2026-08-02 — **Python-PS compose variant**: `docker/compose.python-ps.yml` overrides
  the `person-server` service to build `docker/Dockerfile.python-ps` (Python reference
  from source, permission hotfix included, own data volume — the two implementations'
  SQLite schemas differ). Live-verified: identity variant end to end with the Java
  agents against the containerized Python PS.

- 2026-08-02 — **Adopt aauth-java-library 0.1.1**: the two demo findings are fixed
  upstream — `RequestVerifier` now enforces the RFC 9530 body-vs-digest check itself,
  and the library's default HTTP clients (`TokenExchange`, `CachingJwksFetcher`,
  `Metadata`) pin HTTP/1.1. Demo and aauth-java-person-server pin `0.1.1`; the
  demo-side digest re-check in `AAuthInboundVerifier` and the injected HTTP/1.1
  client in `A2aAuthClient` are removed (`tamperedBodyIsRejected` now passes on the
  library's enforcement alone). `io.github.marcofanti:aauth:0.1.1` resolves from
  Maven Central, so the Docker builders' clone+install bridge is gone — both
  Dockerfiles build straight from Central. Demo-owned HTTP clients
  (`AgentBootstrap`, `ManagedIdentity`, integration tests) keep their explicit
  HTTP/1.1 pins: they don't go through the library's defaults.

- 2026-08-02 — **Consume the released library**: aauth-java-library v0.1.0 was released
  (tag `v0.1.0`, published toward Maven Central via the central-publishing plugin).
  Demo and aauth-java-person-server pinned `0.1.0` (no snapshot), with the Docker
  builders cloning the GitHub tag as a bridge until Central propagation completed.
  Superseded by the 0.1.1 adoption above.

- 2026-08-02 — **Docker packaging** (post-plan): `docker-compose.yml` runs the complete
  edge architecture (7 images, all built from source with the parent `agents/` dir as
  build context + allowlist `.dockerignore`). Live-tested: identity and consent
  variants, unsigned 401 at the edge, full chain with market-analysis hop, consent
  approve via the containerized Java PS. Key decisions:
  - The original "no Docker" rule is obsolete for this codebase: canonical authorities
    come from config, and compose network aliases make `gateway.uma.lab` resolve to the
    gateway container in-network — signed authority and reachable hostname agree.
  - The Person Server runs as **`ps.localhost`** in compose: satisfies the Go
    verifier's https-or-local-dev issuer rule via `*.localhost`, resolves through
    compose DNS in-network, and browsers resolve `*.localhost` to loopback natively
    (consent popup needs no /etc/hosts entry).
  - Plain-HTTP apt mirrors can be blocked; the edge image avoids in-container package
    installs entirely (temurin base already has tar/openssl, binaries fetched via
    BuildKit `ADD` over HTTPS).
  - agents keep container ports 9999/9998 (no clash across containers); the compose
    gateway/aauth configs differ from `gateway/` only in backend hosts and the
    `ps.localhost` issuer.

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
  *Resolved 2026-08-02: fixed upstream in aauth-java-library 0.1.1; the demo-side
  check is removed (see the 0.1.1 adoption entry above).*

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

- 2026-07-31 — **Phase 2 (edge) notes**:
  - agentgateway **route names are global**, not per-bind: duplicated names across the
    9999/9998 binds silently collapsed both ports onto one backend. Names are prefixed
    `sca-`/`maa-`.
  - The gateway forwards the authority **without the port**, so RFC 9421 verification
    at the aauth-service failed against our signed `gateway.uma.lab:9999` until
    `authority_override` was pinned per resource.
  - The Go verifier hard-requires JWT issuers to be https or a local-dev http host —
    `http://ps.uma.lab` can never pass. Edge modes therefore run the Person Server with
    origin `http://127.0.0.1:8765` (`run-demo.sh` restarts it on origin mismatch);
    in-process modes keep `ps.uma.lab`.
  - aauth-service config validation requires `require:user` to be declared in
    `supported_scopes`/`scope_descriptions` before it may appear in
    `default_resource_token_scopes`.
  - In edge modes the agents keep outbound identity signing but disable in-process
    verification (`demo.aauth.mode=edge`); the edge issues its own resource tokens
    signed with `gateway/keys/*.pem` (persistent, generated by `setup-gateway.sh`).

## Deviations from the Python reference

- Reports, business-policy values, and market data are original content (different
  numbers/wording than the reference) — only the flow shapes carry over.
- Backend service name is `aauth-demo-backend` (reference used `supply-chain-api`).
- `/optimization/results/{id}` returns 409 while the run is still in progress
  (reference returned a partially-populated results object).
