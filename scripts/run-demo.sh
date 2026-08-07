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
#   edge / edge-auth / edge-consent — same three levels, but enforced at the
#         agentgateway + aauth-service edge (scripts/setup-gateway.sh); agents run
#         behind the gateway with in-process verification off
#   missions — jwt identity plus the Person Server's mission layer: the backend records
#         a mission, in-scope steps auto-grant via /permission with no prompts, and an
#         out-of-scope purchase step defers to the user
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
missions)
  backend_mode="jwt" sca_mode="jwt" maa_mode="jwt" mission_layer="on"
  ;;
auth-token)
  backend_mode="jwt" sca_mode="auth-token" maa_mode="auth-token"
  ;;
consent)
  backend_mode="jwt" sca_mode="consent" maa_mode="auth-token"
  ;;
edge)
  backend_mode="jwt" sca_mode="edge" maa_mode="edge" edge_variant="identity"
  ;;
edge-auth)
  backend_mode="jwt" sca_mode="edge" maa_mode="edge" edge_variant="auth-token"
  ;;
edge-consent)
  backend_mode="jwt" sca_mode="edge" maa_mode="edge" edge_variant="consent"
  ;;
*)
  echo "Usage: $0 [off|hwk|jwt|auth-token|consent|missions|edge|edge-auth|edge-consent]" >&2
  exit 1
  ;;
esac

mission_layer="${mission_layer:-}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../hosts.env
source "${repo_root}/hosts.env"

edge_variant="${edge_variant:-}"
# Edge modes require a local-dev Person Server origin (Go verifier issuer rules).
if [[ -n ${edge_variant} ]]; then
  ps_origin="http://127.0.0.1:8765"
else
  ps_origin="http://${DEMO_PS_HOST}:8765"
fi

pid_file="${repo_root}/.demo-pids"
log_dir="${repo_root}/logs"
person_token="mytoken"
mkdir -p "${log_dir}"

for host in "${DEMO_PORTAL_HOST}" "${DEMO_GATEWAY_HOST}" "${DEMO_PS_HOST}"; do
  if ! grep -q "${host}" /etc/hosts; then
    echo "ERROR: ${host} is not in /etc/hosts. Add:  127.0.0.1 ${DEMO_PORTAL_HOST} ${DEMO_GATEWAY_HOST} ${DEMO_PS_HOST}" >&2
    exit 1
  fi
done

if [[ -f ${pid_file} ]]; then
  echo "ERROR: ${pid_file} exists — services may already be running. Run scripts/stop-demo.sh first." >&2
  exit 1
fi

# Mission creation stays on the PS default (auto-approve): its deferred-approval path is
# not agent-pollable (GET /pending rejects mission pendings), so the user-consent moment
# in this mode is the out-of-scope permission check, which polls fine. Both PS
# implementations serve the full mission layer; the Java port needs no permission hotfix
# (its /permission deferred branch is handled natively).

if [[ ${mode} != "off" && ${mode} != "hwk" ]]; then
  current_origin="$(curl -sf --max-time 2 "http://127.0.0.1:8765/.well-known/aauth-agent.json" |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["issuer"])' 2>/dev/null || true)"
  if [[ -n ${current_origin} && ${current_origin} != "${ps_origin}" ]]; then
    echo "Person Server origin is ${current_origin}; restarting with ${ps_origin}"
    "${repo_root}/scripts/stop-person-server.sh"
  fi
  AAUTH_PS_PUBLIC_ORIGIN="${ps_origin}" "${repo_root}/scripts/run-person-server.sh"
fi

# The gateway owns ports 9999/9998 in edge modes and must be gone otherwise.
"${repo_root}/scripts/stop-gateway.sh" >/dev/null 2>&1 || true
if [[ -n ${edge_variant} ]]; then
  "${repo_root}/scripts/run-gateway.sh" "${edge_variant}"
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
  shift 2
  local jar="${repo_root}/${name}/target/${name}-0.1.0-SNAPSHOT.jar"
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
    --demo.person-server-url="${ps_origin}" "$@" \
    >"${log_dir}/${name}.log" 2>&1 &
  echo "$! ${name}" >>"${pid_file}"
  echo "Started ${name} (pid $!) [mode=${service_mode}]"
}

approve_pending_registrations() {
  curl -sf -H "Authorization: Bearer ${person_token}" "${ps_origin}/person/registrations" |
    python3 -c 'import sys, json; [print(r["id"]) for r in json.load(sys.stdin)]' 2>/dev/null |
    while read -r pending_id; do
      name=$(curl -sf -X POST -H "Authorization: Bearer ${person_token}" \
        "${ps_origin}/person/registrations/${pending_id}/approve" |
        python3 -c 'import sys, json; print(json.load(sys.stdin).get("agent_name", "?"))' 2>/dev/null || true)
      echo "Approved agent registration: ${name:-${pending_id}}"
    done
}

healthy() {
  curl -sf -o /dev/null "http://${DEMO_GATEWAY_HOST}:9998/.well-known/agent-card.json" &&
    curl -sf -o /dev/null "http://${DEMO_GATEWAY_HOST}:9999/.well-known/agent-card.json" &&
    curl -sf -o /dev/null "http://${DEMO_PORTAL_HOST}:8000/health"
}

# Hostname wiring from hosts.env; the services' own property defaults carry the same names.
maa_flags=(--demo.agent-url="http://${DEMO_GATEWAY_HOST}:9998/")
sca_flags=(
  --demo.agent-url="http://${DEMO_GATEWAY_HOST}:9999/"
  --demo.market-analysis-url="http://${DEMO_GATEWAY_HOST}:9998/"
)
backend_flags=(
  --demo.supply-chain-url="http://${DEMO_GATEWAY_HOST}:9999/"
  --demo.market-analysis-url="http://${DEMO_GATEWAY_HOST}:9998/"
  --demo.cors-allowed-origins="http://${DEMO_PORTAL_HOST}:3050"
)

if [[ -n ${edge_variant} ]]; then
  start_service market-analysis-agent "${maa_mode}" "${maa_flags[@]}" "--server.port=29998"
  start_service supply-chain-agent "${sca_mode}" "${sca_flags[@]}" "--server.port=29999"
else
  start_service market-analysis-agent "${maa_mode}" "${maa_flags[@]}"
  start_service supply-chain-agent "${sca_mode}" "${sca_flags[@]}"
fi
start_service backend "${backend_mode}" "${backend_flags[@]}"

for _ in $(seq 1 60); do
  if [[ ${mode} != "off" && ${mode} != "hwk" ]]; then
    approve_pending_registrations
  fi
  if healthy; then
    echo
    echo "All services up [mode=${mode}]. UI: cd supply-chain-ui && npm run dev  (http://${DEMO_PORTAL_HOST}:3050)"
    echo "Stop with: scripts/stop-demo.sh"
    exit 0
  fi
  sleep 2
done
echo "ERROR: services did not become healthy within 120s (see ${log_dir}/*.log)" >&2
exit 1
