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

Per-service semantics of `demo.aauth.mode`:

- **off** — no signing, no verification (plain A2A over HTTP).
- **hwk** — outbound requests signed with RFC 9421 (`Signature-Key` scheme `hwk`,
  ephemeral Ed25519, pseudonymous); inbound requests must carry a valid signature.
  Unsigned/tampered → 401 + `Accept-Signature`.
- **jwt** — the service registers with the Person Server at startup (stable key +
  ephemeral key, `hwk`-signed `POST /register`, human or `/person`-API approval) and
  signs with `scheme=jwt` carrying its `aa-agent+jwt`. Verifiers require identity and
  resolve the issuer's keys via JWKS discovery.
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

Testing: `./scripts/run-tests.sh [mode|all]` starts the services per mode and runs the
matching tag groups from the `integration-tests` module (`core`, `signed`, `ps`,
`consent`).
