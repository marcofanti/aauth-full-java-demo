# aauth-full-java-demo

Java reimplementation of a multi-agent AAuth demo: a browser UI drives a backend that
delegates to a supply-chain agent over A2A JSON-RPC, which can delegate a second hop to a
market-analysis agent. Every agent-to-agent request is signed with RFC 9421 HTTP Message
Signatures via the [aauth-java-library](../aauth-java-library).

Design and phase plan: [docs/PLAN.md](docs/PLAN.md) · status: [docs/PROGRESS.md](docs/PROGRESS.md)

## Current state: full AAuth flows (identity, auth tokens, consent, refresh)

All phases of [docs/PLAN.md](docs/PLAN.md) are complete, including the agentgateway +
aauth-service verification edge. At startup the backend and supply-chain agent
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
| `auth-token` | Agents demand `aa-auth+jwt` (AAuth draft-11): agent token → 401 `requirement=person-token` → person token from the PS → 401 with a resource token → exchange at the PS with `presented_token` → retry with the auth token, all autonomous |
| `consent` | Like `auth-token`, but the supply-chain agent requires `require:user`: the Person Server defers until the user approves in the consent popup surfaced by the UI |
| `missions` | `jwt` identity plus the Person Server's mission layer: the backend records a mission, in-scope steps auto-grant via `/permission` with no prompts, and an out-of-scope purchase step defers to the user — see [docs/MISSIONS.md](docs/MISSIONS.md) |

Mode semantics: [docs/MODES.md](docs/MODES.md) · consent sequence:
[docs/CONSENT_FLOW.md](docs/CONSENT_FLOW.md)

The demo implements AAuth **draft-11** (`aauth-java-library` 0.3.0). The edge modes
(agentgateway + aauth-service) are unavailable until that verifier supports draft-11; see
[docs/DRAFT11_PLAN.md](docs/DRAFT11_PLAN.md). First-use person-token approvals are granted
automatically by `scripts/approve-person-tokens.sh`, so the consent you see is the
`require:user` one.

Agent tokens auto-renew before expiry (`jkt-jwt` refresh; exercise it with
`AAUTH_AS_AGENT_TOKEN_LIFETIME=90 ./scripts/run-person-server.sh`).

```
Browser (portal.uma.lab:3050) ──REST──► backend (portal.uma.lab:8000)
    ──A2A, signed──► supply-chain-agent (https://sca.uma.lab)
        ──A2A, signed──► market-analysis-agent (https://maa.uma.lab)

https://ps.uma.lab + https://as.uma.lab — aauth-java-person-server: Agent Server, person and auth tokens, consent UI
```

## Integration tests

```bash
./scripts/run-tests.sh all        # cycles every mode: start services -> tagged suites -> stop
./scripts/run-tests.sh consent    # a single mode
```

The `integration-tests` module is skipped in normal `mvn verify` builds (it needs live
services); the script enables it with the tag groups matching each mode, including the
consent approval/denial flows driven through the Person Server's REST API.

## Docker

The draft-11 demo also runs as containers — UI, backend, both agents, the Java Person
Server, a Caddy HTTPS front, an approver and Jaeger:

```bash
./scripts/setup-tls.sh                     # once: *.uma.lab cert + JVM truststore
./scripts/compose-up.sh consent            # or: off | hwk | jwt | auth-token | missions
docker compose down
```

`compose-up.sh` maps the run mode to per-service modes exactly like `run-demo.sh`. The
`caddy` container terminates TLS for `ps`, `as`, `sca` and `maa.uma.lab` with the same
mkcert certificate as the native setup (mounted read-only from
`~/.local/share/uma-lab-tls`) and carries those names as network aliases, so containers
reach each other at their AAuth identifiers through it; it publishes :443, so the
consent popup at `https://ps.uma.lab/ui/consent.html` works from the host browser.
Only one Caddy can own :443 — stop a native one first (`caddy stop`).

Java containers trust the mkcert root through the shared truststore
(`JAVA_TOOL_OPTIONS`). The `approver` container approves agent registrations and
first-use person-token requests; `require:user` consent is left for a human. Keys and
the PS database live in named volumes, so identities survive restarts. From the host:
UI :3050, backend :8000, Jaeger :16686, and the https names above.

Everything builds from source: the sibling aauth-java-person-server is compiled inside
its image (build context is the parent directory), and the library resolves from Maven
Central. The edge modes have no compose variant until a draft-11 edge verifier exists.

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

## Requirements

- JDK 26+, Maven 3.9+
- Node 22+ (UI)
- [Caddy](https://caddyserver.com) and [mkcert](https://github.com/FiloSottile/mkcert)
  (`brew install caddy mkcert`, then `mkcert -install` once)
- `/etc/hosts` entries:
  `127.0.0.1 portal.uma.lab ps.uma.lab as.uma.lab sca.uma.lab maa.uma.lab`

AAuth draft-11 identifiers are https origins without a port. The Person Server
(`https://ps.uma.lab`), its Agent Server (`https://as.uma.lab`) and the two agents
(`https://sca.uma.lab`, `https://maa.uma.lab`) sit behind a local Caddy on :443
([caddy/Caddyfile](caddy/Caddyfile), shared with `aauth-java-person-server`) and listen on
127.0.0.1 only. Run `./scripts/setup-tls.sh` once: it creates the `*.uma.lab` certificate
and a JVM truststore (JDK CAs + mkcert root) that the scripts pass to every Java process.
The backend and UI stay on plain http (`portal.uma.lab`); they are not AAuth identifiers.

Hostnames live in [hosts.env](hosts.env); the scripts source it and pass the names to the
Java services and integration tests.

## Build and run

```bash
./scripts/setup-tls.sh          # once: *.uma.lab cert + JVM truststore
mvn clean verify                # all modules: tests, coverage gate, Spotless, -Werror
mvn -DskipTests package         # just the runnable jars
./scripts/run-demo.sh consent   # full flow: Person Server, registration + auto-approval,
                                # auth tokens, user consent on the supply-chain hop
./scripts/run-demo.sh jwt       # identity only (default when no mode given)
./scripts/run-demo.sh hwk       # pseudonymous signing only (no Person Server)
cd supply-chain-ui && npm install && npm run dev   # UI on http://portal.uma.lab:3050
```

Stop with `./scripts/stop-demo.sh` (and `./scripts/stop-person-server.sh`). The Person
Server is the sibling `../aauth-java-person-server` (`AAUTH_PS_IMPL=java`); the Python
reference `../aauth-person-server` still issues draft-10 tokens, so with it only `off` and
`hwk` work. The consent/admin UI is at `https://ps.uma.lab/ui` (token `mytoken`).

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
| `demo-common` | Adaptation layer to the aauth-java-library: signers (`hwk`/`jwt`), inbound verifier with requirement levels (incl. RFC 9530 body-digest enforcement), bootstrap + `jkt-jwt` refresh (`ManagedIdentity`), exchange-capable client (`A2aAuthClient`) |
| `backend` | Public REST API (portal.uma.lab:8000): async start/progress/results, activity feed |
| `supply-chain-agent` | A2A server (https://sca.uma.lab): policy-driven optimization, optional MAA delegation |
| `market-analysis-agent` | Leaf A2A server (https://maa.uma.lab): canned demand/trend/pattern analyses |
| `supply-chain-ui` | React + Vite dashboard (portal.uma.lab:3050) with the consent banner/popup flow |
| `integration-tests` | Mode-tagged end-to-end suites, run via `scripts/run-tests.sh` |

The `message/send` request body is serialized exactly once (`A2aJson`) and those exact
bytes are what gets signed and what goes on the wire — the invariant that lets RFC 9421
signatures (with `Content-Digest` coverage) verify cleanly.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
