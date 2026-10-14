# Draft: Giving AI agents real identity — an AAuth demo in Java

*Draft for review — not published. Companion repo:
[aauth-full-java-demo](https://github.com/marcofanti/aauth-full-java-demo); library:
[`io.github.marcofanti:aauth`](https://central.sonatype.com/artifact/io.github.marcofanti/aauth)
0.3.0 on Maven Central.*

Agents are starting to call other agents. Today most of those calls are either
anonymous HTTP or a bearer token pasted into a header — which means the receiving
service knows nothing about *who* is calling, *on whose behalf*, or *whether a human
ever agreed to any of this*. AAuth (draft-hardt-oauth-aauth-protocol) is a proposal to
fix that with cryptographic agent identity built on existing standards: RFC 9421 HTTP
Message Signatures, JWTs, and a user-owned "Person Server" that anchors delegation.

I wanted to understand the protocol beyond reading the draft, so I built it: a Java
protocol library (on Maven Central), a Java Person Server, and a multi-agent demo that
turns each protocol concept into a switch you can flip. All three now implement
**draft-11**, published in September. This post walks through what runs, what draft-11
changed, and what I learned.

## The demo in one picture

```
Browser ──REST──► backend ──A2A, signed──► supply-chain-agent ──A2A, signed──► market-analysis-agent
                     │                          │
                     ▼                          ▼
             Person Server + Agent Server  (registration, person and auth tokens, consent, missions)
```

A dashboard asks a backend to optimize a supply chain. The backend delegates to a
supply-chain agent over A2A (JSON-RPC), which delegates market analysis to a third
agent. Every hop is an HTTP request an attacker could tamper with — so every hop is
signed, and the demo lets you dial enforcement up one level at a time:

| Mode | What a request must carry |
|---|---|
| `off` | Nothing — plain HTTP, the baseline to attack |
| `hwk` | A valid RFC 9421 signature (pseudonymous: key embedded in the header) |
| `jwt` | Identity: an `aa-agent+jwt` issued by the Agent Server, sender-constrained to the signing key via `cnf.jwk` |
| `auth-token` | Authorization: a person token, then a resource token, exchanged at the Person Server for an `aa-auth+jwt` with scopes — autonomously |
| `consent` | Same, but the resource demands `require:user`: the exchange blocks until a human approves in the Person Server's consent UI |
| `missions` | A durable mission record governs the run: in-scope steps auto-grant, out-of-scope steps go back to the user |

The identity model uses two keys per agent: a stable Ed25519 key persisted on disk
(the identity anchor, registered once) and an ephemeral per-process key that actually
signs requests. The agent token binds the ephemeral key via its `cnf.jwk` confirmation
claim, and refresh (`jkt-jwt` with a stable-key-signed delegation JWT) rotates the
ephemeral key without re-registering. Kill a service, restart it — it proves continuity
with the stable key and picks up where it left off.

## The part I find most interesting: the authorization ladder

Authorization in AAuth is not "attach a token you already have." The resource tells
the caller, one 401 at a time, what it needs next. In draft-11 the ladder has four
rungs:

1. The agent calls with its agent token. The resource answers
   `401 requirement=person-token`: it wants to know *which person* this is for.
2. The agent asks its Person Server for a **person token** for that resource. The token
   carries a *directed* `sub` — the same person gets a different identifier at every
   resource — and is bound to the agent's key.
3. The agent retries with the person token. Now the resource answers with a 401 that
   *carries a token*: an `aa-resource+jwt` derived from the person token (`ps`, `sub`,
   `presented_jti`) and bound to the caller's key thumbprint.
4. The agent sends the resource token *and the person token it presented* to its
   Person Server, gets an `aa-auth+jwt` with scopes, and retries.

The Person Server sits in the middle of every authorization decision and can check the
exchange against exactly what was presented. In `consent` mode it simply holds the
exchange until a human clicks approve or deny. Two channels — the agent blocks on the
exchange while the UI surfaces the consent URL — and a denial is a first-class outcome,
not an error.

A trace of one consent run shows the whole thing: 72 spans over 9.6 seconds, about 8 of
them the human deciding. Every protocol step is tens of milliseconds.

## What draft-11 changed

Draft-10 to draft-11 was not a wire tweak. The upgrade guide in the spec repository
lists 147 checklist items across four roles. For this demo it meant:

- **A new token and a new round trip.** The person token above. Resource and auth
  tokens lost their `agent` and `act` claims; they name the person instead.
- **`presented_token`.** The exchange now carries the token the agent showed the
  resource, so the Person Server can verify the two against each other.
- **Missions by hash.** Every mission reference is `mission_s256`; the approval response
  carries the mission blob and its hash, and the agent verifies one against the other.
- **Strict identifiers.** Every server identifier is a lowercase `https` origin with no
  port — on verification as well as issuance. `http://ps.uma.lab:8765` is rejected
  outright, even on a laptop.

That last one reshaped the local setup. The demo now runs behind a local Caddy on :443
with an [mkcert](https://github.com/FiloSottile/mkcert) certificate for `*.uma.lab`, so
the Person Server is `https://ps.uma.lab` and the agents are `https://sca.uma.lab` and
`https://maa.uma.lab`. It is more setup than before, and it is also exactly the shape a
real deployment has.

## Missions: correlation for multi-step work

One consent per call doesn't scale to real agent tasks. The missions layer records a
mission (description plus a list of approved tools) at the Person Server; each step
then asks `POST /permission` with the mission's hash. Steps whose action is in the
approved tools are granted instantly and logged; anything else — in the demo, an
`inventory:purchase` the mission never mentioned — defers to the user. The mission log
afterwards reads like an audit trail: approval, each permission decision and who made
it, audit entries with the results, and the completion. The protocol gives you
correlation (every action provably tied to a mission); containment (is this action
*within* the approved authority?) is the Person Server's policy layer on top.

## Cross-implementation interop, for real

The point of a protocol is that implementations don't have to share code. CI runs the
full mode matrix live on every push, over HTTPS, against two Person Servers:

- my **Java** Person Server — all six modes;
- the **Python** reference Person Server — the modes that don't need a draft-11 Person
  Server, until it catches up. The Python *library* already implements draft-11, and
  the Java library's tests check signatures and tokens against it in both directions.

The whole stack also runs in Docker Compose, with a Caddy container providing the same
`https://*.uma.lab` identifiers inside the compose network. The draft-10 version also
ran a **Go** verifier at an agentgateway edge; that one is paused until a draft-11
verifier exists.

## What byte-level interop taught me

These are the bugs I'd never have found reading the spec:

- **HTTP/2 upgrade vs. strict HTTP/1.1 parsers.** The JDK `HttpClient` attempts an
  h2c upgrade by default; uvicorn's h11 parser responds in a way that silently drops
  POST bodies. Every client talking to the Python server pins HTTP/1.1, and the library
  does it in its defaults.
- **Sign the digest, then actually check it.** RFC 9421 signatures cover the
  `Content-Digest` *header* — but if nobody recomputes the digest from the body, a
  tampered body with an intact header verifies. Both reference implementations had this
  gap; the Java library enforces RFC 9530 in its resource-side verifier.
- **Canonical authority comes from config, never from the Host header** — otherwise a
  proxy hop changes the signature base and everything breaks. Behind Caddy that means
  the proxy must preserve `Host`, which it does by default.
- **Byte-exact JSON is part of the wire contract.** The A2A body is signed as bytes,
  so the demo serializes each message exactly once and sends those bytes — no
  re-serialization between signing and sending.
- **Caches must share, not refuse.** The library's JWKS cache rate-limits re-fetches.
  Under concurrent load, callers that hit a cold cache while the first fetch was in
  flight were refused instead of waiting for it. The demo's concurrency test caught it,
  and the fix (single-flight fetches) shipped in the library before the 0.3.0 release.

## Try it

```bash
git clone https://github.com/marcofanti/aauth-full-java-demo
cd aauth-full-java-demo
brew install caddy mkcert && mkcert -install
./scripts/setup-tls.sh            # *.uma.lab certificate + JVM truststore
mvn -DskipTests package
./scripts/run-demo.sh consent     # or: off | hwk | jwt | auth-token | missions
```

The demo needs a few `/etc/hosts` names (`ps`, `as`, `sca`, `maa` and `portal.uma.lab`
pointing at 127.0.0.1) and the Java Person Server checked out next to it; the README has
the details.

The library is a plain Maven dependency:

```xml
<dependency>
  <groupId>io.github.marcofanti</groupId>
  <artifactId>aauth</artifactId>
  <version>0.3.0</version>
</dependency>
```

Everything above — the enforcement ladder, the consent flow, the mission log — is
covered by an integration matrix (`scripts/run-tests.sh all`) that starts the real
services and drives the real Person Server, so the claims in this post are executable.

*Thanks to Dick Hardt for the AAuth draft, and to Christian Posta for the reference
implementations this work builds on.*
