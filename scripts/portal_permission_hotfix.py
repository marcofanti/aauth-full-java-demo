"""Runtime hotfix for the upstream portal app's POST /permission route.

The portal app (``portal.http.app``) mishandles the deferred branch of a permission
check: for an action outside the mission's approved tools, ``governance.post_permission``
returns a ``DeferredResponse``, and the route crashes reading ``.permission`` from it
(HTTP 500). The standalone PS app (``ps.http.app``) handles the same branch correctly
with its ``_json_deferred`` serializer.

This wrapper rewires the running portal app only — no upstream files are modified:
the governance call is wrapped so a deferred outcome travels as an exception to a
handler that serializes it with the portal's own ``_json_deferred``. Everything else
passes through untouched. Run via scripts/run-person-server.sh:

    uvicorn portal_permission_hotfix:app --host 127.0.0.1 --port 8765
"""

import portal.http.app as portal
from ps.models import DeferredResponse

app = portal.app


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
