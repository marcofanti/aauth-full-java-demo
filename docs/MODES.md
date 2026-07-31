# Run modes

`./scripts/run-demo.sh <mode>` selects how much of the AAuth protocol is enforced.
A mode maps to per-service `demo.aauth.mode` values — an agent's mode sets its *inbound*
requirement; the backend only ever needs identity:

| Run mode | backend | supply-chain-agent | market-analysis-agent | Person Server |
|---|---|---|---|---|
| `off` | off | off | off | not needed |
| `hwk` | hwk | hwk | hwk | not needed |
| `jwt` | jwt | jwt | jwt | required |
| `auth-token` | jwt | auth-token | auth-token | required |
| `consent` | jwt | consent | auth-token | required |
| `edge` | jwt | edge | edge | required (127.0.0.1 origin) |
| `edge-auth` | jwt | edge | edge | required (127.0.0.1 origin) |
| `edge-consent` | jwt | edge | edge | required (127.0.0.1 origin) |

Per-service semantics of `demo.aauth.mode`:

- **off** — no signing, no verification (plain A2A over HTTP).
- **hwk** — outbound requests signed with RFC 9421 (`Signature-Key` scheme `hwk`,
  ephemeral Ed25519, pseudonymous); inbound requests must carry a valid signature.
  Unsigned/tampered → 401 + `Accept-Signature`.
- **jwt** — the service registers with the Person Server at startup (stable key +
  ephemeral key, `hwk`-signed `POST /register`, human or `/person`-API approval) and
  signs with `scheme=jwt` carrying its `aa-agent+jwt`. Verifiers require identity and
  resolve the issuer's keys via JWKS discovery. The token auto-renews before expiry
  via `jkt-jwt` refresh (`ManagedIdentity`); cached auth tokens are dropped on
  rotation.
- **auth-token** — inbound requests must additionally carry an `aa-auth+jwt` with
  scopes. Identified callers without one get
  `401` + `AAuth-Requirement: requirement=auth-token, resource-token="…"`; the caller
  exchanges the resource token at the Person Server (`TokenExchange`) and retries.
  Resource tokens are signed with the agent's persistent resource key and have
  `aud` = the Person Server.
- **consent** — like `auth-token`, but the resource-token scope additionally carries
  `require:user`, so the Person Server defers the exchange (202 + pending URL) until a
  human approves. See [CONSENT_FLOW.md](CONSENT_FLOW.md).

Auth tokens are cached per client process for their lifetime — after one approval,
subsequent runs complete without a new consent prompt until the token expires or the
service restarts.

- **edge** (per-service) — outbound identity signing stays on, in-process verification
  is off: the agentgateway routes gateway.uma.lab:9999/:9998 through the aauth-service
  (gRPC ExtAuthz), which enforces the level selected by the run mode's
  `gateway/aauth-config-*.yaml` variant (identity / auth-token / consent) and issues
  the 401 challenges and resource tokens itself. Edge modes run the Person Server on
  origin `http://127.0.0.1:8765` — the Go verifier only accepts http issuers for
  local-development hosts.

Testing: `./scripts/run-tests.sh [mode|all]` starts the services per mode and runs the
matching tag groups from the `integration-tests` module (`core`, `signed`, `ps`,
`consent`).
