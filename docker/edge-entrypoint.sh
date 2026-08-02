#!/bin/sh
# Entry point for the edge image. Roles:
#   aauth-service — generate resource keys if missing, run with the AAUTH_VARIANT config
#   gateway       — run agentgateway with the compose routing config
set -eu

role="${1:-aauth-service}"

case "${role}" in
aauth-service)
  mkdir -p /work/keys
  for key in sca-resource-key maa-resource-key; do
    if [ ! -f "/work/keys/${key}.pem" ]; then
      openssl genpkey -algorithm ed25519 -out "/work/keys/${key}.pem"
      echo "Generated /work/keys/${key}.pem"
    fi
  done
  variant="${AAUTH_VARIANT:-identity}"
  echo "Starting aauth-service with variant ${variant}"
  cd /work
  AAUTH_CONFIG="/work/configs/aauth-config-${variant}.yaml" exec /usr/local/bin/aauth-service
  ;;
gateway)
  exec /usr/local/bin/agentgateway -f /work/configs/agentgateway.yaml
  ;;
*)
  echo "Unknown role: ${role} (expected aauth-service|gateway)" >&2
  exit 1
  ;;
esac
