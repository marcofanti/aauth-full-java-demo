# Draft: Giving AI agents real identity — an AAuth demo in Java

*Draft for review — not published. Companion repo:
[aauth-full-java-demo](https://github.com/marcofanti/aauth-full-java-demo); library:
[`io.github.marcofanti:aauth`](https://central.sonatype.com/artifact/io.github.marcofanti/aauth)
on Maven Central.*

Agents are starting to call other agents. Today most of those calls are either
anonymous HTTP or a bearer token pasted into a header — which means the receiving
service knows nothing about *who* is calling, *on whose behalf*, or *whether a human
ever agreed to any of this*. AAuth (draft-hardt-aauth-protocol) is a proposal to fix
that with cryptographic agent identity built on existing standards: RFC 9421 HTTP
Message Signatures, JWTs, and a user-owned "Person Server" that anchors delegation.

I wanted to understand the protocol beyond reading the draft, so I built it: a Java
protocol library (now on Maven Central), a Java Person Server, and a multi-agent demo
that turns each protocol concept into a switch you can flip. This post walks through
what runs and what I learned.

## The demo in one picture

```
Browser ──REST──► backend ──A2A, signed──► supply-chain-agent ──A2A, signed──► market-analysis-agent
                              │
                              ▼
                        Person Server  (agent registration, token exchange, consent UI, missions)
```

A dashboard asks a backend to optimize a supply chain. The backend delegates to a
supply-chain agent over A2A (JSON-RPC), which delegates market analysis to a third
agent. Every hop is an HTTP request an attacker could tamper with — so every hop is
signed, and the demo lets you dial enforcement up one level at a time:

| Mode | What a request must carry |
|---|---|
| `off` | Nothing — plain HTTP, the baseline to attack |
| `hwk` | A valid RFC 9421 signature (pseudonymous: key embedded in the header) |
| `jwt` | Identity: an `aa-agent+jwt` issued by the Person Server, sender-constrained to the signing key via `cnf.jwk` |
| `auth-token` | Authorization: a 401 challenge carries a resource token; the caller exchanges it at the Person Server for an `aa-auth+jwt` with scopes — autonomously |
| `consent` | Same, but the resource demands `require:user`: the exchange blocks until a human approves in the Person Server's consent UI |
| `edge` variants | The same three levels, enforced at an agentgateway + external-authz edge instead of in-process |
| `missions` | A durable mission record governs the run: in-scope steps auto-grant, out-of-scope steps go back to the user |

The identity model uses two keys per agent: a stable Ed25519 key persisted on disk
(the identity anchor, registered once with the Person Server) and an ephemeral
per-process key that actually signs requests. The agent token binds the ephemeral key
via its `cnf.jwk` confirmation claim, and refresh (`jkt-jwt` with a stable-key-signed
delegation JWT) rotates the ephemeral key without re-registering. Kill a service,
restart it — it proves continuity with the stable key and picks up where it left off.

## The part I find most interesting: the three-party exchange

Authorization in AAuth is not "attach a token you already have." At `auth-token`
level, the resource answers an identified-but-unauthorized caller with a 401 that
*carries a token* — an `aa-resource+jwt` naming the caller's key thumbprint and the
scopes on offer, audience-bound to the caller's own Person Server. The caller
exchanges it there for an `aa-auth+jwt` and retries. The user's authority server sits
in the middle of every authorization decision, and in `consent` mode it simply holds
the exchange until a human clicks approve or deny. Two channels — the agent blocks on
the exchange while the UI surfaces the consent URL — and a denial is a first-class
outcome, not an error.

## Missions: correlation for multi-step work

One consent per call doesn't scale to real agent tasks. The missions layer records a
mission (description plus a list of approved tools) at the Person Server; each step
then asks `POST /permission` with the mission reference. Steps whose action is in the
approved tools are granted instantly and logged; anything else — in the demo, an
`inventory:purchase` the mission never mentioned — defers to the user. The mission log
afterwards reads like an audit trail: `mission_approved`, each permission decision and
who made it, and audit entries with the results. The protocol gives you correlation
(every action provably tied to a mission); containment (is this action *within* the
approved authority?) is the Person Server's policy layer on top.

## Cross-implementation interop, for real

The point of a protocol is that implementations don't have to share code. This stack
mixes three languages at once:

- **Java** agents and backend (this demo, on the new library),
- the **Python** Person Server reference — or my **Java** port, drop-in behind the
  same scripts,
- a **Go** verifier at the edge (agentgateway's external-authz service) checking the
  same signatures the Java code produces.

The whole thing also runs as a Docker Compose stack, including a variant that swaps
the Java Person Server for the Python one with a one-line compose override.

## What byte-level interop taught me

These are the bugs I'd never have found reading the spec:

- **HTTP/2 upgrade vs. strict HTTP/1.1 parsers.** The JDK `HttpClient` attempts an
  h2c upgrade by default; uvicorn's h11 parser responds in a way that silently drops
  POST bodies. Every client talking to the Python server pins HTTP/1.1 now — the
  library does it in its defaults since 0.1.1.
- **Sign the digest, then actually check it.** RFC 9421 signatures cover the
  `Content-Digest` *header* — but if nobody recomputes the digest from the body, a
  tampered body with an intact header verifies. Both reference implementations had
  this gap; the Java library closes it in its resource-side verifier since 0.1.1
  (RFC 9530 enforcement).
- **Canonical authority comes from config, never from the Host header** — otherwise a
  proxy hop changes the signature base and everything breaks. The related edge lesson:
  gateways love to rewrite authority; an explicit `authority_override` at the gateway
  keeps the signed string and the verified string identical.
- **Byte-exact JSON is part of the wire contract.** The A2A body is signed as bytes,
  so the demo serializes each message exactly once and sends those bytes — no
  re-serialization between signing and sending.

## Try it

```bash
git clone https://github.com/marcofanti/aauth-full-java-demo
cd aauth-full-java-demo
mvn -DskipTests package
./scripts/run-demo.sh consent    # or: off | hwk | jwt | auth-token | missions | edge…
```

The library is a plain Maven dependency:

```xml
<dependency>
  <groupId>io.github.marcofanti</groupId>
  <artifactId>aauth</artifactId>
  <version>0.1.1</version>
</dependency>
```

Everything above — the enforcement ladder, the consent flow, the mission log, the
edge — is covered by an integration matrix (`scripts/run-tests.sh all`) that starts
the real services and drives the real Person Server, so the claims in this post are
executable.

*Thanks to Dick Hardt for the AAuth draft, and to the aauth-person-server reference
implementation this demo runs against.*
