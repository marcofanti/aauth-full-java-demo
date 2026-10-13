# Run modes

`./scripts/run-demo.sh <mode>` selects how much of the AAuth (draft-11) protocol is
enforced. A mode maps to per-service `demo.aauth.mode` values — an agent's mode sets its
*inbound* requirement; the backend only ever needs identity:

| Run mode | backend | supply-chain-agent | market-analysis-agent | Person Server |
|---|---|---|---|---|
| `off` | off | off | off | not needed |
| `hwk` | hwk | hwk | hwk | not needed |
| `jwt` | jwt | jwt | jwt | required |
| `auth-token` | jwt | auth-token | auth-token | required |
| `consent` | jwt | consent | auth-token | required |
| `missions` | jwt | jwt | jwt | required (see [MISSIONS.md](MISSIONS.md)) |

The edge modes (`edge`, `edge-auth`, `edge-consent`: verification at the agentgateway +
aauth-service edge) are unavailable until that verifier supports draft-11;
`run-demo.sh` refuses them.

Identifiers are https origins without a port: the agents are `https://sca.uma.lab` and
`https://maa.uma.lab`, the Person Server `https://ps.uma.lab` and its Agent Server
`https://as.uma.lab`, all behind the local Caddy front (see the README).

Per-service semantics of `demo.aauth.mode`:

- **off** — no signing, no verification (plain A2A over HTTP).
- **hwk** — outbound requests signed with RFC 9421 (`Signature-Key` scheme `hwk`,
  ephemeral Ed25519, pseudonymous); inbound requests must carry a valid signature and a
  `Content-Digest` matching the body. Unsigned/tampered → 401 + `Accept-Signature`.
- **jwt** — the service registers with the Agent Server at startup (stable key +
  ephemeral key, `hwk`-signed `POST /register`, human or `/person`-API approval) and
  signs with `scheme=jwt` carrying its `aa-agent+jwt`. Verifiers require an agent token
  and resolve the issuer's keys via JWKS discovery. The token auto-renews before expiry
  via `jkt-jwt` refresh (`ManagedIdentity`); cached person and auth tokens are dropped on
  rotation. A caller without an agent token gets `401` + `requirement=agent-token`.
- **auth-token** — inbound requests must carry an `aa-auth+jwt` granting the agent's
  scope. The caller climbs the draft-11 ladder, all autonomously:
  1. agent token → `401 AAuth-Requirement: requirement=person-token`;
  2. the caller requests a person token (`aa-person+jwt`) for that resource from its PS
     (`person_token_endpoint`) and retries with it;
  3. person token → `401 requirement=auth-token; resource-token="…"`. The resource
     token is minted by the library from the verified person token (`ps`, `sub`,
     `presented_jti`, `mission_s256`), signed with the agent's persistent resource key,
     `aud` = the PS;
  4. the caller sends the resource token and the person token (`presented_token`) to the
     PS's `auth_token_endpoint` and retries with the resulting `aa-auth+jwt`.

  The first person token an agent requests for a resource needs the person's approval
  (PS-12); `scripts/approve-person-tokens.sh`, started by `run-demo.sh`, grants those.
- **consent** — like `auth-token`, but the resource-token scope additionally carries
  `require:user`, so the Person Server defers the exchange (202 + pending URL) until a
  human approves. See [CONSENT_FLOW.md](CONSENT_FLOW.md).

Person and auth tokens are cached per client process; after one approval, later runs
complete without a new consent prompt until the token nears expiry (5-minute margin) or
the service restarts. Draft-11 tokens carry the person as a directed identifier: the same
person has a different `sub` at each resource.

Testing: `./scripts/run-tests.sh [mode|all]` starts the services per mode and runs the
matching tag groups from the `integration-tests` module (`core`, `signed`, `ps`,
`consent`, `missions`). With `AAUTH_PS_IMPL=python`, `all` runs only `off` and `hwk`: the
Python reference still issues draft-10 tokens.
