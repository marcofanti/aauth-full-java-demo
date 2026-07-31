# aauth-full-java-demo

Java reimplementation of a multi-agent AAuth demo: a browser UI drives a backend that
delegates to a supply-chain agent over A2A JSON-RPC, which can delegate a second hop to a
market-analysis agent. Every agent-to-agent request is signed with RFC 9421 HTTP Message
Signatures via the [aauth-java-library](../aauth-java-library).

Design and phase plan: [docs/PLAN.md](docs/PLAN.md) · status: [docs/PROGRESS.md](docs/PROGRESS.md)

## Current state: full AAuth flows (identity, auth tokens, user consent)

Phases 0, 1 and 3–6 are complete. At startup the backend and supply-chain agent
register with the local [aauth-person-server](../aauth-person-server) (stable Ed25519
key persisted in `.aauth-demo/`, `hwk`-signed registration, human/API approval, then an
`aa-agent+jwt` bound to a per-process ephemeral key). Every A2A hop is signed with
`scheme=jwt` and verified in-process; unsigned or tampered requests get a 401 challenge.

Run modes (`./scripts/run-demo.sh <mode>`):

| Mode | Behavior |
|---|---|
| `off` | Plain HTTP |
| `hwk` | Pseudonymous RFC 9421 signing (no Person Server) |
| `jwt` | Agent identity: `aa-agent+jwt`, verifiers require identity |
| `auth-token` | Agents demand `aa-auth+jwt`: 401 + resource token → autonomous Person Server exchange → retry |
| `consent` *(default demo)* | Like `auth-token`, but the supply-chain agent requires `require:user`: the Person Server defers until the user approves in the consent popup surfaced by the UI |

Mode semantics: [docs/MODES.md](docs/MODES.md) · consent sequence:
[docs/CONSENT_FLOW.md](docs/CONSENT_FLOW.md)

Agent tokens auto-renew before expiry (`jkt-jwt` refresh; exercise it with
`AAUTH_AS_AGENT_TOKEN_LIFETIME=90 ./scripts/run-person-server.sh`). Still to come: the
agentgateway edge (rest of phase 2).

## Integration tests

```bash
./scripts/run-tests.sh all        # cycles every mode: start services -> tagged suites -> stop
./scripts/run-tests.sh consent    # a single mode
```

The `integration-tests` module is skipped in normal `mvn verify` builds (it needs live
services); the script enables it with the tag groups matching each mode, including the
consent approval/denial flows driven through the Person Server's REST API.

## Tracing

```bash
./scripts/setup-tracing.sh      # one-time: OTel Java agent + Jaeger binary into tools/
./scripts/run-jaeger.sh         # collector + UI on http://127.0.0.1:16686
./scripts/run-demo.sh consent   # tracing turns on automatically when tools/ is populated
```

Run an optimization, then open Jaeger and select the `backend` service: one trace spans
all three services and shows the AAuth choreography — the 401 challenges, Person Server
exchanges, and the consent wait. The Person Server itself is not instrumented (Python),
so its endpoints appear as client spans only. Stop with `./scripts/stop-jaeger.sh`.

```
Browser (portal.uma.lab:3050) ──REST──► backend portal.uma.lab:8000
    ──A2A──► supply-chain-agent gateway.uma.lab:9999
        ──A2A──► market-analysis-agent gateway.uma.lab:9998
```

`ps.uma.lab` is reserved for the Person Server (phases 2+); `grafana.uma.lab` for
observability (phase 7).

## Requirements

- JDK 26+, Maven 3.9+
- Node 22+ (UI)
- `/etc/hosts` entries: `127.0.0.1 portal.uma.lab gateway.uma.lab ps.uma.lab`

## Build and run

```bash
mvn clean verify                # all modules: tests, coverage gate, Spotless, -Werror
mvn -DskipTests package         # just the runnable jars
./scripts/run-demo.sh consent   # full flow: Person Server, registration + auto-approval,
                                # auth tokens, user consent on the supply-chain hop
./scripts/run-demo.sh jwt       # identity only (default when no mode given)
./scripts/run-demo.sh hwk       # pseudonymous signing only (no Person Server)
cd supply-chain-ui && npm install && npm run dev   # UI on http://portal.uma.lab:3050
```

Stop with `./scripts/stop-demo.sh` (and `./scripts/stop-person-server.sh`). The Person
Server is the sibling `../aauth-person-server` checkout, run unmodified with uma.lab
origins; its consent/admin UI is at `http://ps.uma.lab:8765/ui` (token `mytoken`).

## Try it

Click **Optimize Laptop Supply Chain**, or enter a custom prompt. Keywords steer the
canned logic:

- `cost`/`budget`, `urgent`/`speed` — optimization goal
- `inventory`/`stock` — adds a buffer review
- `perform market analysis` — triggers the second agent hop (supply-chain →
  market-analysis); add `quarter`/`year` to change the analysis horizon

Or via curl:

```bash
curl -s -X POST http://portal.uma.lab:8000/optimization/start \
  -H 'Content-Type: application/json' \
  -d '{"customPrompt":"optimize laptop supply chain and perform market analysis"}'
curl -s http://portal.uma.lab:8000/optimization/progress/<requestId>
curl -s http://portal.uma.lab:8000/optimization/results/<requestId>
```

## Modules

| Module | Purpose |
|---|---|
| `a2a-support` | Minimal A2A JSON-RPC types, deterministic JSON codec, signing-aware client |
| `demo-common` | Adaptation layer to the aauth-java-library: HWK signer + inbound verifier (incl. RFC 9530 body-digest enforcement) |
| `backend` | Public REST API (portal.uma.lab:8000): async start/progress/results, activity feed |
| `supply-chain-agent` | A2A server (gateway.uma.lab:9999): policy-driven optimization, optional MAA delegation |
| `market-analysis-agent` | Leaf A2A server (gateway.uma.lab:9998): canned demand/trend/pattern analyses |
| `supply-chain-ui` | React + Vite dashboard (portal.uma.lab:3050) with consent banner/popup scaffolding |

The `message/send` request body is serialized exactly once (`A2aJson`) and those bytes are
what the `RequestSigner` sees and what goes on the wire — the prerequisite for RFC 9421
signing landing cleanly in phase 4.

## License

MIT — see [LICENSE](LICENSE).
