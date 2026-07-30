#!/usr/bin/env bash
# Starts the three Java services as local processes with no gateway ("mode0"):
#   market-analysis-agent :9998, supply-chain-agent :9999, backend :8000
# AAuth HWK signing + in-process verification is ON by default; disable per service with
# demo.aauth.mode=off. PIDs land in .mode0-pids, logs in logs/.
# Requires jars built via: mvn -DskipTests package
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="${repo_root}/.mode0-pids"
log_dir="${repo_root}/logs"
mkdir -p "${log_dir}"

for host in portal.uma.lab gateway.uma.lab; do
  if ! grep -q "${host}" /etc/hosts; then
    echo "ERROR: ${host} is not in /etc/hosts. Add:  127.0.0.1 portal.uma.lab gateway.uma.lab ps.uma.lab" >&2
    exit 1
  fi
done

if [[ -f ${pid_file} ]]; then
  echo "ERROR: ${pid_file} exists — services may already be running. Run scripts/stop-mode0.sh first." >&2
  exit 1
fi

start_service() {
  local name="$1"
  local jar="${repo_root}/$1/target/$1-0.1.0-SNAPSHOT.jar"
  if [[ ! -f ${jar} ]]; then
    echo "ERROR: ${jar} not found. Build first: mvn -DskipTests package" >&2
    exit 1
  fi
  java -jar "${jar}" >"${log_dir}/${name}.log" 2>&1 &
  echo "$! ${name}" >>"${pid_file}"
  echo "Started ${name} (pid $!)"
}

wait_for() {
  local name="$1" url="$2"
  for _ in $(seq 1 30); do
    if curl -sf -o /dev/null "${url}"; then
      echo "${name} is up: ${url}"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: ${name} did not become healthy at ${url} within 30s (see ${log_dir}/${name}.log)" >&2
  exit 1
}

start_service market-analysis-agent
start_service supply-chain-agent
start_service backend

wait_for market-analysis-agent "http://gateway.uma.lab:9998/.well-known/agent-card.json"
wait_for supply-chain-agent "http://gateway.uma.lab:9999/.well-known/agent-card.json"
wait_for backend "http://portal.uma.lab:8000/health"

echo
echo "All services up. UI: cd supply-chain-ui && npm run dev  (http://portal.uma.lab:3050)"
echo "Stop with: scripts/stop-mode0.sh"
