#!/usr/bin/env bash
# Runs the sibling aauth-person-server repo (unmodified) with this demo's uma.lab origins.
# Portal (Agent Provider + Person Server + consent UI) listens on http://ps.uma.lab:8765.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ps_repo="$(cd "${repo_root}/../aauth-person-server" && pwd)"
pid_file="${repo_root}/.person-server-pid"
log_dir="${repo_root}/logs"
mkdir -p "${log_dir}"

if [[ -f ${pid_file} ]] && kill -0 "$(cat "${pid_file}")" 2>/dev/null; then
  echo "Person Server already running (pid $(cat "${pid_file}"))"
  exit 0
fi

if [[ ! -x "${ps_repo}/.venv/bin/uvicorn" ]]; then
  echo "Setting up Person Server virtualenv (one-time)..."
  (cd "${ps_repo}" && uv venv .venv && uv pip install --python .venv/bin/python -e ".[dev]") \
    >>"${log_dir}/person-server-setup.log" 2>&1
fi

export AAUTH_DATABASE_URL="sqlite:///${ps_repo}/aauth.db"
export AAUTH_PS_SIGNING_KEY_PATH="${ps_repo}/.aauth/ps-signing-key.pem"
export AAUTH_AS_SIGNING_KEY_PATH="${ps_repo}/.aauth/as-signing-key.pem"
export AAUTH_PS_PUBLIC_ORIGIN="http://ps.uma.lab:8765"
export AAUTH_AS_PUBLIC_ORIGIN="http://ps.uma.lab:8765"
export AAUTH_PS_ADMIN_TOKEN="mytoken"
export AAUTH_AS_PERSON_TOKEN="mytoken"
export AAUTH_PS_INSECURE_DEV="false"
export AAUTH_AS_INSECURE_DEV="false"
# Override to a short value (min 60) to exercise jkt-jwt token refresh in a demo session.
export AAUTH_AS_AGENT_TOKEN_LIFETIME="${AAUTH_AS_AGENT_TOKEN_LIFETIME:-86400}"

# exec so the recorded pid is uvicorn itself, not a wrapper subshell.
(cd "${ps_repo}" && exec .venv/bin/uvicorn portal.http.app:app --host 127.0.0.1 --port 8765) \
  >"${log_dir}/person-server.log" 2>&1 &
echo "$!" >"${pid_file}"
echo "Started Person Server (pid $!)"

for _ in $(seq 1 30); do
  if curl -sf -o /dev/null "http://ps.uma.lab:8765/.well-known/aauth-agent.json"; then
    echo "Person Server is up: http://ps.uma.lab:8765"
    exit 0
  fi
  sleep 1
done
echo "ERROR: Person Server did not become healthy (see ${log_dir}/person-server.log)" >&2
exit 1
