# aauth-full-java-demo

Java reimplementation of a multi-agent AAuth demo: a browser UI drives a backend that
delegates to a supply-chain agent over A2A JSON-RPC, which can delegate a second hop to a
market-analysis agent. Every agent-to-agent request is signed with RFC 9421 HTTP Message
Signatures via the [aauth-java-library](../aauth-java-library).

Design and phase plan: [docs/PLAN.md](docs/PLAN.md) · status: [docs/PROGRESS.md](docs/PROGRESS.md)

## Current state: HWK-signed, in-process verification

Phases 0, 1 and 3 are complete. Both A2A hops are signed with the AAuth `hwk` scheme
(pseudonymous Ed25519, per-process ephemeral keys, signature covering
`@method @authority @path signature-key content-digest content-type`) and verified
in-process by the receiving agent — unsigned or tampered requests get a 401 with an
`Accept-Signature` challenge. Set `demo.aauth.mode=off` per service to fall back to
plain HTTP. The gateway/Person-Server modes (identity, auth tokens, user consent)
arrive in phases 2 and 4–6.

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
./scripts/run-mode0.sh          # starts the three services, health-checked
cd supply-chain-ui && npm install && npm run dev   # UI on http://portal.uma.lab:3050
```

Stop everything with `./scripts/stop-mode0.sh`.

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
