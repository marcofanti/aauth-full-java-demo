#!/usr/bin/env bash
# Starts the demo services with the given AAuth mode:
#   off — plain HTTP, no signing
#   hwk — pseudonymous RFC 9421 signing, verified in-process
#   jwt — agent identity: services register with the Person Server (started automatically),
#         sign with aa-agent+jwt, and verifiers require identity  [default]
#   auth-token — agents additionally demand aa-auth+jwt; callers exchange the 401's
#         resource token at the Person Server autonomously
#   consent — like auth-token, but the supply-chain agent's resource token carries
#         require:user, so the Person Server defers until a human approves
# Pending registrations are auto-approved via the Person Server's /person API.
# Requires jars built via: mvn -DskipTests package
set -euo pipefail

mode="${1:-jwt}"
# Per-service modes: an agent's mode sets its inbound requirement; the backend only ever
# needs identity (jwt). In consent mode only the user-facing hop (SCA) demands consent.
case "${mode}" in
off | hwk | jwt)
  backend_mode="${mode}" sca_mode="${mode}" maa_mode="${mode}"
  ;;
auth-token)
  backend_mode="jwt" sca_mode="auth-token" maa_mode="auth-token"
  ;;
consent)
  backend_mode="jwt" sca_mode="consent" maa_mode="auth-token"
  ;;
*)
  echo "Usage: $0 [off|hwk|jwt|auth-token|consent]" >&2
  exit 1
  ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="${repo_root}/.demo-pids"
log_dir="${repo_root}/logs"
person_token="mytoken"
mkdir -p "${log_dir}"

for host in portal.uma.lab gateway.uma.lab ps.uma.lab; do
  if ! grep -q "${host}" /etc/hosts; then
    echo "ERROR: ${host} is not in /etc/hosts. Add:  127.0.0.1 portal.uma.lab gateway.uma.lab ps.uma.lab" >&2
    exit 1
  fi
done

if [[ -f ${pid_file} ]]; then
  echo "ERROR: ${pid_file} exists — services may already be running. Run scripts/stop-demo.sh first." >&2
  exit 1
fi

if [[ ${mode} != "off" && ${mode} != "hwk" ]]; then
  "${repo_root}/scripts/run-person-server.sh"
fi

# Tracing is on when the OTel Java agent has been downloaded (scripts/setup-tracing.sh)
# and a collector is reachable; spans go to Jaeger via OTLP (scripts/run-jaeger.sh).
otel_agent="${repo_root}/tools/opentelemetry-javaagent.jar"
tracing=""
if [[ -f ${otel_agent} ]]; then
  tracing="on"
  echo "Tracing enabled (OTel agent -> http://127.0.0.1:4318, UI http://127.0.0.1:16686)"
fi

start_service() {
  local name="$1" service_mode="$2"
  local jar="${repo_root}/$1/target/$1-0.1.0-SNAPSHOT.jar"
  if [[ ! -f ${jar} ]]; then
    echo "ERROR: ${jar} not found. Build first: mvn -DskipTests package" >&2
    exit 1
  fi
  local agent_flag=""
  if [[ -n ${tracing} ]]; then
    agent_flag="-javaagent:${otel_agent}"
  fi
  OTEL_SERVICE_NAME="${name}" \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_EXPORTER_OTLP_ENDPOINT="http://127.0.0.1:4318" \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none \
    java ${agent_flag:+"${agent_flag}"} -jar "${jar}" --demo.aauth.mode="${service_mode}" \
    >"${log_dir}/${name}.log" 2>&1 &
  echo "$! ${name}" >>"${pid_file}"
  echo "Started ${name} (pid $!) [mode=${service_mode}]"
}

approve_pending_registrations() {
  curl -sf -H "Authorization: Bearer ${person_token}" "http://ps.uma.lab:8765/person/registrations" |
    python3 -c 'import sys, json; [print(r["id"]) for r in json.load(sys.stdin)]' 2>/dev/null |
    while read -r pending_id; do
      name=$(curl -sf -X POST -H "Authorization: Bearer ${person_token}" \
        "http://ps.uma.lab:8765/person/registrations/${pending_id}/approve" |
        python3 -c 'import sys, json; print(json.load(sys.stdin).get("agent_name", "?"))' 2>/dev/null || true)
      echo "Approved agent registration: ${name:-${pending_id}}"
    done
}

healthy() {
  curl -sf -o /dev/null "http://gateway.uma.lab:9998/.well-known/agent-card.json" &&
    curl -sf -o /dev/null "http://gateway.uma.lab:9999/.well-known/agent-card.json" &&
    curl -sf -o /dev/null "http://portal.uma.lab:8000/health"
}

start_service market-analysis-agent "${maa_mode}"
start_service supply-chain-agent "${sca_mode}"
start_service backend "${backend_mode}"

for _ in $(seq 1 60); do
  if [[ ${mode} != "off" && ${mode} != "hwk" ]]; then
    approve_pending_registrations
  fi
  if healthy; then
    echo
    echo "All services up [mode=${mode}]. UI: cd supply-chain-ui && npm run dev  (http://portal.uma.lab:3050)"
    echo "Stop with: scripts/stop-demo.sh"
    exit 0
  fi
  sleep 2
done
echo "ERROR: services did not become healthy within 120s (see ${log_dir}/*.log)" >&2
exit 1
