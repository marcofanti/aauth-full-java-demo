#!/usr/bin/env bash
# Starts the demo services with the given AAuth (draft-11) mode:
#   off — plain HTTP, no signing
#   hwk — pseudonymous RFC 9421 signing, verified in-process
#   jwt — agent identity: services register with the Agent Server (started automatically),
#         sign with aa-agent+jwt, and verifiers require identity  [default]
#   auth-token — agents demand aa-auth+jwt: callers present a person token, receive a
#         resource token, and exchange it at the Person Server autonomously
#   consent — like auth-token, but the supply-chain agent's resource token carries
#         require:user, so the Person Server defers until a human approves
#   missions — jwt identity plus the Person Server's mission layer: the backend records
#         a mission, in-scope steps auto-grant via /permission with no prompts, and an
#         out-of-scope purchase step defers to the user
# AAuth identifiers are https origins without a port (https://ps.uma.lab,
# https://sca.uma.lab, ...), served by the shared Caddy front (scripts/run-caddy.sh).
# Pending registrations and first-use person-token consents are auto-approved.
# Requires: jars built (mvn -DskipTests package) and scripts/setup-tls.sh run once.
set -euo pipefail

mode="${1:-jwt}"
# Per-service modes: an agent's mode sets its inbound requirement; the backend only ever
# needs identity (jwt). In consent mode only the user-facing hop (SCA) demands consent.
case "${mode}" in
off | hwk | jwt)
  backend_mode="${mode}" sca_mode="${mode}" maa_mode="${mode}"
  ;;
missions)
  backend_mode="jwt" sca_mode="jwt" maa_mode="jwt"
  ;;
auth-token)
  backend_mode="jwt" sca_mode="auth-token" maa_mode="auth-token"
  ;;
consent)
  backend_mode="jwt" sca_mode="consent" maa_mode="auth-token"
  ;;
edge | edge-auth | edge-consent)
  echo "ERROR: the edge modes need a draft-11 edge verifier; agentgateway's aauth-service" \
    "predates draft-11 (see docs/DRAFT11_PLAN.md)." >&2
  exit 1
  ;;
*)
  echo "Usage: $0 [off|hwk|jwt|auth-token|consent|missions]" >&2
  exit 1
  ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../hosts.env
source "${repo_root}/hosts.env"

ps_origin="https://${DEMO_PS_HOST}"
as_origin="https://${DEMO_AS_HOST}"
sca_url="https://${DEMO_SCA_HOST}/"
maa_url="https://${DEMO_MAA_HOST}/"
pid_file="${repo_root}/.demo-pids"
log_dir="${repo_root}/logs"
person_token="mytoken"
tls_dir="${HOME}/.local/share/uma-lab-tls"
ca_file="$(mkcert -CAROOT)/rootCA.pem"
mkdir -p "${log_dir}"

for host in "${DEMO_PORTAL_HOST}" "${DEMO_PS_HOST}" "${DEMO_AS_HOST}" "${DEMO_SCA_HOST}" "${DEMO_MAA_HOST}"; do
  if ! grep -q "${host}" /etc/hosts; then
    echo "ERROR: ${host} is not in /etc/hosts. Add:  127.0.0.1 ${DEMO_PORTAL_HOST} ${DEMO_PS_HOST}" \
      "${DEMO_AS_HOST} ${DEMO_SCA_HOST} ${DEMO_MAA_HOST}" >&2
    exit 1
  fi
done
if [[ ! -f "${tls_dir}/truststore.p12" ]]; then
  echo "ERROR: no local TLS setup. Run scripts/setup-tls.sh once." >&2
  exit 1
fi

if [[ -f ${pid_file} ]]; then
  echo "ERROR: ${pid_file} exists — services may already be running. Run scripts/stop-demo.sh first." >&2
  exit 1
fi

"${repo_root}/scripts/run-caddy.sh"

if [[ ${mode} != "off" && ${mode} != "hwk" ]]; then
  AAUTH_PS_PUBLIC_ORIGIN="${ps_origin}" AAUTH_AS_PUBLIC_ORIGIN="${as_origin}" \
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
    java ${agent_flag:+"${agent_flag}"} \
    -Djavax.net.ssl.trustStore="${tls_dir}/truststore.p12" -Djavax.net.ssl.trustStorePassword=changeit \
    -jar "${jar}" --demo.aauth.mode="${service_mode}" \
    --demo.person-server-url="${ps_origin}" --demo.agent-server-url="${as_origin}" "$@" \
    >"${log_dir}/${name}.log" 2>&1 &
  echo "$! ${name}" >>"${pid_file}"
  echo "Started ${name} (pid $!) [mode=${service_mode}]"
}

approve_pending_registrations() {
  curl -sf -H "Authorization: Bearer ${person_token}" "http://127.0.0.1:8765/person/registrations" |
    python3 -c 'import sys, json; [print(r["id"]) for r in json.load(sys.stdin)]' 2>/dev/null |
    while read -r pending_id; do
      name=$(curl -sf -X POST -H "Authorization: Bearer ${person_token}" \
        "http://127.0.0.1:8765/person/registrations/${pending_id}/approve" |
        python3 -c 'import sys, json; print(json.load(sys.stdin).get("agent_name", "?"))' 2>/dev/null || true)
      echo "Approved agent registration: ${name:-${pending_id}}"
    done
}

healthy() {
  curl -sf -o /dev/null --cacert "${ca_file}" "${maa_url}.well-known/agent-card.json" &&
    curl -sf -o /dev/null --cacert "${ca_file}" "${sca_url}.well-known/agent-card.json" &&
    curl -sf -o /dev/null "http://${DEMO_PORTAL_HOST}:8000/health"
}

# Agents listen on loopback only; Caddy fronts them as https://{sca,maa}.uma.lab.
maa_flags=(--server.address=127.0.0.1 --demo.agent-url="${maa_url}")
sca_flags=(--server.address=127.0.0.1 --demo.agent-url="${sca_url}" --demo.market-analysis-url="${maa_url}")
backend_flags=(
  --demo.supply-chain-url="${sca_url}"
  --demo.market-analysis-url="${maa_url}"
  --demo.cors-allowed-origins="http://${DEMO_PORTAL_HOST}:3050"
)

start_service market-analysis-agent "${maa_mode}" "${maa_flags[@]}"
start_service supply-chain-agent "${sca_mode}" "${sca_flags[@]}"
start_service backend "${backend_mode}" "${backend_flags[@]}"

# Draft-11: the first person token an agent requests for a resource needs the person's
# approval. Approve those automatically (they are not the consent this demo showcases);
# require:user consent on the exchange still waits for a human in consent modes.
if [[ ${mode} != "off" && ${mode} != "hwk" ]]; then
  "${repo_root}/scripts/approve-person-tokens.sh" "http://127.0.0.1:8765" "${person_token}" \
    >"${log_dir}/person-token-approver.log" 2>&1 &
  echo "$! person-token-approver" >>"${pid_file}"
fi

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
