#!/usr/bin/env bash
# Runs the Jaeger v2 all-in-one binary: OTLP on 4317 (gRPC) / 4318 (HTTP), UI on 16686.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jaeger_bin="${repo_root}/tools/jaeger"
pid_file="${repo_root}/.jaeger-pid"
log_dir="${repo_root}/logs"
mkdir -p "${log_dir}"

if [[ ! -x ${jaeger_bin} ]]; then
  echo "ERROR: ${jaeger_bin} not found. Run scripts/setup-tracing.sh first." >&2
  exit 1
fi

if [[ -f ${pid_file} ]] && kill -0 "$(cat "${pid_file}")" 2>/dev/null; then
  echo "Jaeger already running (pid $(cat "${pid_file}"))"
  exit 0
fi

"${jaeger_bin}" >"${log_dir}/jaeger.log" 2>&1 &
echo "$!" >"${pid_file}"
echo "Started Jaeger (pid $!)"

for _ in $(seq 1 30); do
  if curl -sf -o /dev/null "http://127.0.0.1:16686/"; then
    echo "Jaeger UI: http://127.0.0.1:16686"
    exit 0
  fi
  sleep 1
done
echo "ERROR: Jaeger did not become healthy (see ${log_dir}/jaeger.log)" >&2
exit 1
