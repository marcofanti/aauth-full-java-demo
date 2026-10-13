#!/usr/bin/env bash
# Starts the shared Caddy HTTPS front (caddy/Caddyfile) unless one is already running.
# aauth-java-person-server ships the same Caddyfile; whichever repo starts first owns it,
# and neither reloads it (a reload from a different config would drop hosts).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if curl -sf -o /dev/null --max-time 2 http://localhost:2019/config/; then
  echo "Caddy already running (admin API on localhost:2019)"
  exit 0
fi
if [[ ! -f "${HOME}/.local/share/uma-lab-tls/cert.pem" ]]; then
  echo "ERROR: no *.uma.lab certificate. Run scripts/setup-tls.sh first." >&2
  exit 1
fi
mkdir -p "${repo_root}/logs"
# caddy start daemonizes but keeps inherited stdout open; detach it so callers can pipe us.
caddy start --config "${repo_root}/caddy/Caddyfile" --adapter caddyfile \
  >"${repo_root}/logs/caddy.log" 2>&1
echo "Caddy started: https://{ps,as,sca,maa}.uma.lab (log: logs/caddy.log)"
