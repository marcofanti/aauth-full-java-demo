# Missions mode

`scripts/run-demo.sh missions` runs the demo with the Person Server's mission layer on
top of `jwt` identity mode. A mission is a durable, user-visible record of delegated
authority: the agent proposes it once, and every later action refers back to it — the
Person Server logs each decision against the mission, and actions outside the mission's
approved tools defer to the user.

Requires the Python Person Server (`../aauth-person-server`); the Java port has no
mission endpoints.

## Flow

1. `POST /missions/start` on the backend (`{"description": …, "products": […]}`,
   both optional). The backend proposes a mission to the Person Server (`POST /mission`,
   signed `scheme=jwt`) with two approved tools: `supply-chain:optimize` and
   `market-analysis:analyze`.
2. For each product, the backend asks `POST /permission` with the mission reference.
   `supply-chain:optimize` is in the approved tools, so the Person Server grants it
   immediately — no prompt — and the full A2A chain runs. The result is appended to the
   mission log via `POST /audit`.
3. The closing step asks permission for `inventory:purchase` — **not** in the approved
   tools. The Person Server defers (202); the backend surfaces the consent URL and code
   (`interaction_required`), and the user's approval or denial is the step's outcome.
   A denial is a recorded decision, not an error: the mission still completes.
4. `GET /missions/progress/{id}` shows status and per-step outcomes;
   `GET /missions/{s256}` on the Person Server (admin bearer) shows the full mission
   log: `mission_approved`, each `permission` decision with who decided it, and the
   `audit` entries.

## REST walkthrough

```bash
scripts/run-demo.sh missions

# Start a mission
curl -s -X POST http://portal.uma.lab:8000/missions/start \
  -H 'Content-Type: application/json' \
  -d '{"products":["laptop"]}'                 # → {"missionId": …}

# Watch it run; the optimize step needs no prompt
curl -s http://portal.uma.lab:8000/missions/progress/<missionId>

# When status is interaction_required, decide as the user (or open interactionUrl):
curl -s "http://ps.uma.lab:8765/consent?code=<interactionCode>"        # → pending_id
curl -s -X POST http://ps.uma.lab:8765/consent/<pending_id>/decision \
  -H 'Content-Type: application/json' -d '{"approved":false}'

# The mission completes with the purchase recorded as denied; read the audit trail:
curl -s -H 'Authorization: Bearer mytoken' \
  http://ps.uma.lab:8765/missions/<missionS256>
```

Integration tests: `scripts/run-tests.sh missions` (tag `missions`).

## Adaptations to the upstream Person Server

- Mission proposals use the Person Server's default **auto-approve**: its deferred
  mission-approval path is not agent-pollable (`GET /pending/{id}` rejects mission
  pendings with 404). The user-consent moment in this mode is therefore the
  out-of-scope permission check, which polls fine. `MissionClient` already handles a
  deferred proposal (202 → consent → poll) should upstream add the poll route.
- The portal app 500s on deferred permission checks (it misses the `DeferredResponse`
  branch its standalone PS app handles). `scripts/portal_permission_hotfix.py` rewires
  the running app at startup — no upstream files are modified; see the module docstring.
  Both gaps are worth reporting upstream.
