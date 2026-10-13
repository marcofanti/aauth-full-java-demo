#!/usr/bin/env bash
# Starts the containerized demo in an AAuth run mode:
#   scripts/compose-up.sh [off|hwk|jwt|auth-token|consent|missions]   (default: jwt)
# Maps the run mode to per-service modes exactly like scripts/run-demo.sh, then runs
# `docker compose up -d --build`. Stop with: docker compose down
set -euo pipefail

mode="${1:-jwt}"
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
*)
  echo "Usage: $0 [off|hwk|jwt|auth-token|consent|missions]" >&2
  exit 1
  ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "${HOME}/.local/share/uma-lab-tls/truststore.p12" ]]; then
  echo "ERROR: no local TLS setup. Run scripts/setup-tls.sh once." >&2
  exit 1
fi
if curl -sf -o /dev/null --max-time 2 http://localhost:2019/config/; then
  echo "ERROR: a native Caddy owns :443 (admin API on localhost:2019). Stop it: caddy stop" >&2
  exit 1
fi

cd "${repo_root}"
BACKEND_MODE="${backend_mode}" SCA_MODE="${sca_mode}" MAA_MODE="${maa_mode}" \
  docker compose up -d --build
echo "Compose stack up [mode=${mode}]: UI http://portal.uma.lab:3050 · Jaeger http://localhost:16686"
