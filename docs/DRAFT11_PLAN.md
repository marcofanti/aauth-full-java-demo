# Moving to AAuth draft-11

**Status (2026-10-13):** demo ported on branch `draft11` against the final library
draft11 (strict) — see the PROGRESS.md decision log. Live verification waits on the
Java PS's rework onto that library. The plan below is kept as the record of the decision.

Source: `draft-hardt-oauth-aauth-protocol-11` (2026-09-25; note the renamed draft —
`-oauth-` was added) and the official per-role checklists in
https://github.com/dickhardt/AAuth/tree/main/upgrade-10-to-11 (IDs like `AG-21`,
`RS-32`, and `PS-31` below refer to those).

## What changes for this demo

Draft-10 changed wire details. Draft-11 changes the **flow**:

| | draft-10 (today) | draft-11 |
|---|---|---|
| First call to a resource needing authorization | agent token | **person token** (`aa-person+jwt`, new) |
| Challenge when no token | `requirement=auth-token` + resource token | `requirement=person-token` (no params), then resource token |
| Exchange | `POST token_endpoint {resource_token}` | `POST auth_token_endpoint {resource_token, presented_token}` |
| Resource token claims | `agent`, `mission` | `ps`, `sub`, `presented_jti` (+ `mission_s256`, `tenant`); no `agent` |
| Auth token claims | `agent`, `act`, `mission` | `ps`, REQUIRED directed `sub`, `mission_s256`; no `agent`/`act` |
| Missions | `AAuth-Mission` header, `{approver, s256}` | `mission_s256` everywhere; new approval body; completion at `{mission_endpoint}/{s256}` |
| `access_mode` | `aauth-access-token` | `session-token`; new `person-token` |

Unchanged: `hwk` and `jwt` modes (agent-identity access), signing, `jkt-jwt` refresh.

## Dependency order

The demo can't move first. Nearly every change consumes library APIs that don't exist
yet, and three-party flows need a PS that issues person tokens.

1. **aauth-java-library 0.3.0 (draft-11)**, roughly:
   - Agent role: `PersonTokens.request(...)` (AG-10), person token cache (AG-11),
     `TokenExchange` sends `presented_token` to `auth_token_endpoint` (AG-20/21),
     new challenge/auth-token verification (AG-22/23), `requirement=person-token` and
     `202` auth-token delivery parsing (AG-26), new error codes (AG-60..62).
   - Resource role: person-token verification (RS-10/12), `ChallengeBuilder`
     `person-token` spec (RS-11), new resource-token claims derived from the
     presented token (RS-30..34), auth-token checks without `agent`/`act` (RS-40..42).
   - Missions: `mission_s256` helpers, new approval-response parser (AG-30..33).
   - Metadata: `auth_token_endpoint`, `person_token_endpoint`, new `access_mode` values.
2. **aauth-java-person-server on 0.3.0**: `person_token_endpoint` (PS-10..13),
   directed per-resource `sub` (PS-20), `presented_token` verification (PS-30..34),
   mission rework, revocation format.
3. **This demo** (below).

## Demo changes (step 3)

- `AAuthInboundVerifier`: `AUTH_TOKEN` requirement verifies a person token or an auth
  token; a bare agent token gets `requirement=person-token`; resource tokens are
  built from the presented token (RS-30..34). `A2aEndpoint` gains the new branch.
- `A2aAuthClient`: on `requirement=person-token`, get or reuse a person token for the
  resource and retry with it; on `requirement=auth-token`, exchange with
  `presented_token` = the token just presented; cache per resource; drop caches on
  key rotation (AG-11). Handle `202` delivery if the library exposes it.
- `ManagedIdentity`: refresh at the 5-minute margin, top down (AG-70).
- `MissionClient`/`MissionService`: `mission_s256`, new approval body (decode the
  `mission` blob, verify `s256`), completion via the mission endpoint (AG-30..33),
  and request person tokens with `mission_s256`.
- Integration tests: the consent/auth-token ITs assert the person-token round, and
  missions ITs use the new shapes. `hwk`/`jwt`/`off` ITs are unaffected.
- Docs: MODES.md, CONSENT_FLOW.md, MISSIONS.md sequence diagrams; README mode table.

## Interop impact (decision needed)

| Counterparty | draft-11 status (2026-10-12) | Effect |
|---|---|---|
| Python PS (`aauth-person-server`) | not updated (last commit Aug 5); pins `aauth==0.3.2` | python-ps CI leg fails `auth-token`/`consent`/`missions` |
| Python library (`aauth` on PyPI) | **0.5.0 implements -11** (Oct 6) | PS could adopt it, but that's a PS rewrite, not a pin bump |
| Go verifier (`extauth-aauth-resource`) | untouched since May (pre-draft-10) | all edge modes break for three-party flows |

So the two-implementation conformance matrix drops to Java-only for the PS-backed
modes until the Python PS catches up. Options:
- **A. Clean cut**: the demo targets -11 only; python-ps leg runs `off`/`hwk`/`jwt`
  and edge runs `edge` (identity) only, with the gap documented.
- **B. Dual-draft**: keep a `-10` code path selectable per run. Doubles the client and
  verifier logic; against the "replace, don't deprecate" rule unless needed for a
  specific audience.
- **C. Wait**: hold the demo at -10 until the Python PS ships -11.

**Decided (2026-10-12): A — clean cut.** The demo targets draft-11 only, after
steps 1–2 land. At that point the python-ps CI leg is reduced to `off`/`hwk`/`jwt`,
edge to `edge` (identity), and the reduced matrix is stated in README/PROGRESS.
The full matrix returns as the Python PS and Go verifier adopt -11.
