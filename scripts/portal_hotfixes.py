"""Runtime hotfixes for the upstream Python portal app (no upstream files modified).

Two gaps in the upstream stack are patched on the running app:

1. **Deferred /permission 500** — for an action outside the mission's approved tools,
   ``governance.post_permission`` returns a ``DeferredResponse`` and the portal route
   crashes reading ``.permission`` from it. The standalone PS app handles this branch
   with ``_json_deferred``; the wrapper routes the deferred outcome to the same
   serializer via an exception handler.

2. **Draft-10 ``Ed25519`` alg rejected** — aauth-java-library >= 0.2.x mints
   ``aa-resource+jwt`` with the fully-specified ``alg: Ed25519`` (RFC 9864, AAuth
   draft-10). The Python library's ``verify_resource_token`` calls PyJWT with
   ``algorithms=["EdDSA"]`` only, so draft-10 tokens die with an uncaught
   ``InvalidAlgorithmError`` (HTTP 500). The wrapper registers ``Ed25519`` as a PyJWT
   algorithm (same Ed25519 crypto as ``EdDSA``) and widens allowlists that permit
   ``EdDSA`` to also permit ``Ed25519`` — within the aauth token module only.

Run via scripts/run-person-server.sh:

    uvicorn portal_hotfixes:app --host 127.0.0.1 --port 8765
"""

import jwt as pyjwt
import portal.http.app as portal
from ps.models import DeferredResponse

app = portal.app


# --- Fix 1: deferred /permission ------------------------------------------------------


class _DeferredPermission(Exception):
    def __init__(self, deferred: DeferredResponse):
        self.deferred = deferred


_original_post_permission = app.state.ps.governance.post_permission


def _post_permission_with_deferral(request):
    out = _original_post_permission(request)
    if isinstance(out, DeferredResponse):
        raise _DeferredPermission(out)
    return out


app.state.ps.governance.post_permission = _post_permission_with_deferral


@app.exception_handler(_DeferredPermission)
async def _deferred_permission_handler(_request, exc: _DeferredPermission):
    return portal._json_deferred(exc.deferred)


# --- Fix 2: accept fully-specified Ed25519 alg (AAuth draft-10 / RFC 9864) ------------

try:
    pyjwt.register_algorithm("Ed25519", pyjwt.algorithms.OKPAlgorithm())
except ValueError:
    pass  # already registered (e.g. reload)


class _Draft10TolerantJwt:
    """Delegates to PyJWT, widening EdDSA allowlists to also accept Ed25519."""

    def __getattr__(self, name):
        return getattr(pyjwt, name)

    def decode(self, token, key=None, algorithms=None, **kwargs):
        if algorithms and "EdDSA" in algorithms and "Ed25519" not in algorithms:
            algorithms = [*algorithms, "Ed25519"]
        return pyjwt.decode(token, key, algorithms=algorithms, **kwargs)


import aauth.tokens.resource_token as _resource_token

_resource_token.jwt = _Draft10TolerantJwt()
