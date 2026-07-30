# Progress

Phase status for the plan in [PLAN.md](PLAN.md). Update on phase completion or when a
design decision deviates from the plan or from the Python reference.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Scaffold (POMs, quality gates, UI skeleton, docs) | **done** (2026-07-30) |
| 1 | Business core without auth (a2a-support, agents, backend, UI) | **done** (2026-07-30) — `mvn clean verify` green (44 tests, 4×80% coverage gates), UI click-through and 3-service curl flow verified live |
| 2 | Infra (Person Server, gateway + aauth-service configs, scripts) | pending — `run-mode0.sh`/`stop-mode0.sh` exist; gateway/PS setup not started |
| 3 | Library integration gate (`demo-common` adaptation layer, signed spike) | **done** (2026-07-30) — library finished (11/11 phases) and installed; `demo-common` wraps it; both A2A hops HWK-signed and verified in-process, live-tested (signed chain completes, unsigned → 401 + `Accept-Signature`) |
| 4 | Bootstrap + Mode 1 (agent tokens, `scheme=jwt`) | pending — needs Person Server (phase 2); HWK signing already in place |
| 5 | Mode 3 (401 → resource token → PS exchange) | pending — needs phases 2 + 4 |
| 6 | User-consent flow | pending — needs phase 5 |
| 7 | Observability (OTel → Jaeger) | pending |
| 8 | Integration tests + docs | pending |

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

## Deviations from the Python reference

- Reports, business-policy values, and market data are original content (different
  numbers/wording than the reference) — only the flow shapes carry over.
- Backend service name is `aauth-demo-backend` (reference used `supply-chain-api`).
- `/optimization/results/{id}` returns 409 while the run is still in progress
  (reference returned a partially-populated results object).
