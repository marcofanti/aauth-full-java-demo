#!/usr/bin/env bash
# Starts the AAuth edge: aauth-service (gRPC ExtAuthz :7070 + HTTP :8081) with the given
# policy variant, then agentgateway on 9999/9998.
#   scripts/run-gateway.sh [identity|auth-token|consent]     (default: identity)
set -euo pipefail

variant="${1:-identity}"
case "${variant}" in identity | auth-token | consent) ;; *)
  echo "Usage: $0 [identity|auth-token|consent]" >&2
  exit 1
  ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="${repo_root}/.gateway-pids"
log_dir="${repo_root}/logs"
mkdir -p "${log_dir}"

for binary in aauth-service agentgateway; do
  if [[ ! -x "${repo_root}/tools/${binary}" ]]; then
    echo "ERROR: tools/${binary} not found. Run scripts/setup-gateway.sh first." >&2
    exit 1
  fi
done

if [[ -f ${pid_file} ]]; then
  echo "ERROR: ${pid_file} exists — the edge may already be running. Run scripts/stop-gateway.sh first." >&2
  exit 1
fi

# aauth-service resolves keys/ relative to its working directory.
(cd "${repo_root}/gateway" && AAUTH_CONFIG="aauth-config-${variant}.yaml" \
  exec "${repo_root}/tools/aauth-service") >"${log_dir}/aauth-service.log" 2>&1 &
echo "$! aauth-service" >>"${pid_file}"
echo "Started aauth-service (pid $!) [variant=${variant}]"

"${repo_root}/tools/agentgateway" -f "${repo_root}/gateway/agentgateway.yaml" \
  >"${log_dir}/agentgateway.log" 2>&1 &
echo "$! agentgateway" >>"${pid_file}"
echo "Started agentgateway (pid $!)"

for _ in $(seq 1 30); do
  if curl -sf -o /dev/null "http://127.0.0.1:8081/.well-known/aauth-resource.json" -H "Host: gateway.uma.lab:9999" &&
    nc -z 127.0.0.1 9999 2>/dev/null && nc -z 127.0.0.1 9998 2>/dev/null; then
    echo "Edge is up: agentgateway on 9999/9998, aauth-service on 7070/8081"
    exit 0
  fi
  sleep 1
done
echo "ERROR: edge did not become healthy (see ${log_dir}/aauth-service.log, ${log_dir}/agentgateway.log)" >&2
exit 1
