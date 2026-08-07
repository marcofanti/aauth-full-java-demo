# Live deployment plan (non-local)

Goal: run the demo on a public host so anyone can open the dashboard, watch the
signed agent chain, and click through a real consent — without a laptop-local setup.

**Recommended shape (assumption, see Open decisions):** one small VM, the existing
Docker Compose stack, and a Caddy reverse proxy terminating TLS for a handful of
subdomains on a real domain. Everything below is phased so the work lands
incrementally and each phase is verifiable on its own.

## Why this is more than `docker compose up` on a VM

Three protocol-level facts drive the plan; none of them are ordinary web-app concerns:

1. **The signed authority must equal the public hostname.** RFC 9421 signatures cover
   `@authority`. Today every canonical base carries a port
   (`gateway.uma.lab:9999`). Live, callers reach services as
   `https://sca.<domain>` (implicit :443), so every canonical base, gateway
   `authority_override`, and `demo.agent-url` must become the port-less public name —
   and the proxy must never rewrite `Host`.
2. **Issuers must be `https` outside local dev.** Both the Go edge verifier and the
   Person Server's own settings treat plain-http issuers as local-dev-only. The
   `ps.localhost` compose alias exists solely to satisfy that rule over http; with
   real TLS it disappears — the PS origin is simply `https://ps.<domain>`.
3. **Consent must work from a stranger's browser.** The consent URL embedded in
   deferred responses comes from `AAUTH_PS_PUBLIC_ORIGIN`; set to the public https
   origin, the popup works for any visitor. That's the payoff of the whole exercise.

TLS termination is safe for the signatures: the covered components
(`@method @authority @path signature-key content-digest content-type`) are all
protocol-version independent, so browsers speaking h2 to Caddy while Caddy speaks
HTTP/1.1 to the services changes nothing that is signed.

## Target topology

```
                        ┌──────────────── VM (Docker) ────────────────┐
https://demo.<domain>   │ caddy ──► ui (nginx)                        │
https://portal.<domain> │       ──► backend :8000                     │
https://sca.<domain>    │       ──► supply-chain-agent :9999 (*)      │
https://maa.<domain>    │       ──► market-analysis-agent :9998 (*)   │
https://ps.<domain>     │       ──► person-server :8765               │
https://jaeger.<domain> │       ──► jaeger :16686   [basic-auth]      │
                        └─────────────────────────────────────────────┘
(*) or via agentgateway when running an edge variant — the extAuthz chain is
    unchanged; only the public entry moves from :9999/:9998 to hostnames.
```

Six DNS A records to one VM IP. Caddy gets certificates from Let's Encrypt
automatically. Only 80/443 are exposed; every service port stays on the compose
network (today's compose publishes 8765/9999/9998/8000/3050/16686 to the host —
those `ports:` entries all go away except the proxy's).

## Phases

### Phase 0 — decisions (blocking, cheap)

See **Open decisions** at the end: domain, hosting provider, which enforcement mode
runs publicly, and whether the Java or Python PS is the live one.

### Phase 1 — parameterize scheme + domain in the compose stack

The local `hosts.env` work already parameterized hostnames for scripts/Java/tests;
this phase does the same for the containerized stack, plus the two live-only axes
(scheme, port-less authorities):

- `deploy/.env` (gitignored, from a committed `.env.example`): `DEMO_DOMAIN`,
  `DEMO_SCHEME=https`, admin tokens, variant.
- Compose override `docker/compose.live.yml`: removes host `ports:` from services,
  adds the `caddy` service, injects the public origins into each service's
  environment (`demo.agent-url=https://sca.${DEMO_DOMAIN}/`,
  `AAUTH_PS_PUBLIC_ORIGIN=https://ps.${DEMO_DOMAIN}`, CORS to the UI origin, UI
  build arg `VITE_API_BASE_URL=https://portal.${DEMO_DOMAIN}`).
- Gateway/edge configs: the aauth-config variants gain a live flavor where
  `authority_override` and issuers are the https public names (env-substituted at
  container start, as the edge entrypoint already does for variant selection).
- Verify locally first: the whole live topology can be rehearsed on the laptop with
  Caddy's `tls internal` (self-signed CA) and `/etc/hosts` entries before touching a
  VM — catches authority/issuer mistakes without DNS or Let's Encrypt in the loop.

### Phase 2 — TLS proxy

- `caddy` service + committed `Caddyfile.template`: one `reverse_proxy` block per
  subdomain; `header_up Host {host}` is Caddy's default (host preserved — required);
  HTTP→HTTPS redirect; certificate storage on a named volume so renewals survive
  restarts.
- Jaeger (and optionally the PS `/ui` console) behind Caddy `basicauth`.

### Phase 3 — hardening (a public demo is still public)

- **Tokens:** replace `mytoken` (PS admin + person API) with generated secrets from
  `.env`; never committed.
- **Registration approval:** drop the auto-`approver` container in live. Manual
  approval through the PS console is both safer and a better demo — a human approving
  an agent's registration is the story. (Compose `profiles` keep the approver
  available for CI/local.)
- **Consent endpoints:** consent codes are high-entropy capability URLs, acceptable
  for a demo; the `/person` admin API is bearer-protected with the new secret. The
  PS console UI goes behind Caddy basic-auth.
- **Blast-radius limits:** `restart: unless-stopped` everywhere, JVM memory caps
  (`-XX:MaxRAMPercentage`), Caddy rate limiting on `/optimization/start` and
  `/missions/start` (each run fans out signed calls — cheap DoS lever otherwise),
  named volumes for PS data + stable keys so identities survive redeploys.
- **Firewall:** provider-level allowlist of 22/80/443 only.

### Phase 4 — build + deploy pipeline

- Extend the existing GitHub Actions: build the five images on push to `main`, push
  to GHCR (`ghcr.io/marcofanti/aauth-demo-*`), tag by commit SHA.
- Compose on the VM pulls images instead of building (build once in CI, not on a
  small VM). Deploy = `docker compose pull && docker compose up -d`, either manually
  over SSH or as a workflow with an SSH deploy step.
- Rollback = re-point the image tag and `up -d` again.

### Phase 5 — live verification + ops

- Smoke: the integration suite already reads hostnames from system properties —
  point `demo.*.host` at the public names and run the `core`+`signed` groups against
  the live stack from anywhere (PS-dependent groups too if the run is allowed to
  create registrations).
- A `scripts/live-smoke.sh` doing the one-curl version: health, one optimization to
  `completed`, one consent round-trip.
- Monitoring: Jaeger is already wired (OTel agents attach in-image); add a Caddy
  access log and a `docker compose ps`-based healthcheck cron that alerts (email is
  enough for a demo).
- Backup: nightly tar of the named volumes (PS SQLite + keys) — minutes of work,
  saves re-registering everything.

## Sizing & cost (assumption: single VM)

3 JVM services (≈300–400 MB each with caps) + PS + Caddy + Jaeger + UI nginx →
comfortable on **4 GB RAM / 2 vCPU**. Hetzner CX22 (~€4/mo) or DigitalOcean 4 GB
(~$24/mo) both fine; add ~$1 for snapshots. Let's Encrypt is free. DNS is whatever
the domain already costs.

## Risks / gotchas (learned the hard way locally)

| Risk | Mitigation |
|---|---|
| Proxy rewrites `Host` → every signature fails | Caddy preserves Host by default; phase-1 local rehearsal catches regressions |
| A canonical base keeps its `:port` → authority mismatch only in live | All live origins flow from `DEMO_DOMAIN` in one `.env`; no hand-edited duplicates |
| PS behind https but told its origin is http → issuer mismatch in every minted token | `AAUTH_PS_PUBLIC_ORIGIN` is part of the same `.env`, set once |
| Java PS lacks mission endpoints | Live missions variant = `compose.python-ps.yml` (hotfix baked in); or skip missions live |
| Public `/optimization/start` abused | Caddy rate limit + JVM memory caps |
| Anyone registers an agent | Manual approval in live (auto-approver off) |
| Cert renewal breaks silently | Caddy volume + healthcheck cron hitting https |

## Open decisions (answer these, then Phase 1 can start)

1. **Domain / subdomains** — you appear to control `itnaf.org` (it's in your hosts
   file); `ps.demo.itnaf.org` etc. would work. Or a dedicated cheap domain.
2. **Provider** — Hetzner (cheapest), DigitalOcean, or an existing VM you already run.
3. **Public enforcement mode** — recommend `consent` as the default story
   (identity + exchange + human approval, the full pitch), with `AAUTH_VARIANT` still
   switchable. Edge and missions variants can come later.
4. **Which PS live** — Java (core protocol, our code end-to-end) vs Python
   (enables missions). Recommend Java first; the python-ps override is one flag away.
5. **Deploy trigger** — manual SSH deploy first, promote to a GitHub Actions deploy
   job once it's boring.
