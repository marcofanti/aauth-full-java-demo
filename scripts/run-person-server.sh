#!/usr/bin/env bash
# Runs the sibling Person Server repo (unmodified) with this demo's uma.lab origins.
# Portal (Agent Provider + Person Server + consent UI) listens on http://ps.uma.lab:8765.
# Default implementation is the Python reference (../aauth-person-server); set
# AAUTH_PS_IMPL=java to run the Java port (../aauth-java-person-server) instead —
# same env contract, same routes, same origin.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../hosts.env
source "${repo_root}/hosts.env"
impl="${AAUTH_PS_IMPL:-python}"
pid_file="${repo_root}/.person-server-pid"
log_dir="${repo_root}/logs"
mkdir -p "${log_dir}"

if [[ -f ${pid_file} ]] && kill -0 "$(cat "${pid_file}")" 2>/dev/null; then
  echo "Person Server already running (pid $(cat "${pid_file}"))"
  exit 0
fi

if [[ ${impl} == "java" ]]; then
  ps_repo="$(cd "${repo_root}/../aauth-java-person-server" && pwd)"
  jar="${ps_repo}/target/aauth-java-person-server-0.1.0-SNAPSHOT.jar"
  if [[ ! -f ${jar} ]]; then
    echo "Building Java Person Server (one-time)..."
    (cd "${ps_repo}" && mvn -q -DskipTests package) >>"${log_dir}/person-server-setup.log" 2>&1
  fi
  # Own DB file and signing keys: the Java schema is not byte-compatible with the
  # Python repo's Alembic DB, and the Java server writes a .pub sibling next to keys.
  export AAUTH_DATABASE_URL="sqlite:///${ps_repo}/aauth-demo.db"
  export AAUTH_PS_SIGNING_KEY_PATH="${ps_repo}/.aauth/demo-ps-signing-key.pem"
  export AAUTH_AS_SIGNING_KEY_PATH="${ps_repo}/.aauth/demo-as-signing-key.pem"
else
  ps_repo="$(cd "${repo_root}/../aauth-person-server" && pwd)"
  if [[ ! -x "${ps_repo}/.venv/bin/uvicorn" ]]; then
    echo "Setting up Person Server virtualenv (one-time)..."
    (cd "${ps_repo}" && uv venv .venv && uv pip install --python .venv/bin/python -e ".[dev]") \
      >>"${log_dir}/person-server-setup.log" 2>&1
  fi
  export AAUTH_DATABASE_URL="sqlite:///${ps_repo}/aauth.db"
  export AAUTH_PS_SIGNING_KEY_PATH="${ps_repo}/.aauth/ps-signing-key.pem"
  export AAUTH_AS_SIGNING_KEY_PATH="${ps_repo}/.aauth/as-signing-key.pem"
fi

# Edge modes need a local-development origin (the Go verifier rejects http://ps.uma.lab
# issuers); run-demo.sh overrides this to http://127.0.0.1:8765 for them.
export AAUTH_PS_PUBLIC_ORIGIN="${AAUTH_PS_PUBLIC_ORIGIN:-http://${DEMO_PS_HOST}:8765}"
export AAUTH_AS_PUBLIC_ORIGIN="${AAUTH_AS_PUBLIC_ORIGIN:-${AAUTH_PS_PUBLIC_ORIGIN}}"
export AAUTH_PS_ADMIN_TOKEN="mytoken"
export AAUTH_AS_PERSON_TOKEN="mytoken"
export AAUTH_PS_INSECURE_DEV="false"
export AAUTH_AS_INSECURE_DEV="false"
# Override to a short value (min 60) to exercise jkt-jwt token refresh in a demo session.
export AAUTH_AS_AGENT_TOKEN_LIFETIME="${AAUTH_AS_AGENT_TOKEN_LIFETIME:-86400}"

# exec so the recorded pid is the server itself, not a wrapper subshell.
if [[ ${impl} == "java" ]]; then
  (cd "${ps_repo}" && exec java -jar "${jar}" --server.port=8765 --server.address=127.0.0.1) \
    >"${log_dir}/person-server.log" 2>&1 &
else
  # portal_hotfixes wraps the portal app: deferred /permission handling and
  # draft-10 Ed25519 alg tolerance (see the module docstring).
  (cd "${ps_repo}" && PYTHONPATH="${repo_root}/scripts${PYTHONPATH:+:${PYTHONPATH}}" \
    exec .venv/bin/uvicorn portal_hotfixes:app --host 127.0.0.1 --port 8765) \
    >"${log_dir}/person-server.log" 2>&1 &
fi
echo "$!" >"${pid_file}"
echo "Started Person Server (${impl}, pid $!)"

for _ in $(seq 1 30); do
  if curl -sf -o /dev/null "http://${DEMO_PS_HOST}:8765/.well-known/aauth-agent.json"; then
    echo "Person Server is up: http://${DEMO_PS_HOST}:8765"
    exit 0
  fi
  sleep 1
done
echo "ERROR: Person Server did not become healthy (see ${log_dir}/person-server.log)" >&2
exit 1
