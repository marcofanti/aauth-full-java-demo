#!/usr/bin/env bash
# Stops the Jaeger collector started by run-jaeger.sh.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="${repo_root}/.jaeger-pid"

if [[ ! -f ${pid_file} ]]; then
  echo "Nothing to stop: ${pid_file} not found."
  exit 0
fi

pid="$(cat "${pid_file}")"
if kill "${pid}" 2>/dev/null; then
  echo "Stopped Jaeger (pid ${pid})"
else
  echo "Jaeger (pid ${pid}) was not running"
fi
rm -f "${pid_file}"
