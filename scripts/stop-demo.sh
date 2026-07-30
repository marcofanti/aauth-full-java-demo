#!/usr/bin/env bash
# Stops the services started by run-demo.sh (leaves the Person Server running;
# use stop-person-server.sh for that).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="${repo_root}/.demo-pids"

if [[ ! -f ${pid_file} ]]; then
  echo "Nothing to stop: ${pid_file} not found."
  exit 0
fi

while read -r pid name; do
  if kill "${pid}" 2>/dev/null; then
    echo "Stopped ${name} (pid ${pid})"
  else
    echo "${name} (pid ${pid}) was not running"
  fi
done <"${pid_file}"

rm -f "${pid_file}"
