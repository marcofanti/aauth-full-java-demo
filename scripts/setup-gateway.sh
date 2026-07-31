#!/usr/bin/env bash
# One-time setup for the AAuth edge (plan phase 2):
#   - agentgateway binary (christian-posta fork, the pair validated with aauth-service)
#   - aauth-service binary (extauth-aauth-resource): gRPC ExtAuthz verifier + HTTP sidecar
#   - Ed25519 resource signing keys for the two protected resources
# Binaries land in tools/ and keys in gateway/keys/ (both gitignored).
set -euo pipefail

agentgateway_version="0.11.4"
extauth_version="0.0.1"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="${repo_root}/tools"
keys_dir="${repo_root}/gateway/keys"
mkdir -p "${tools_dir}" "${keys_dir}"

arch="$(uname -m)"
case "${arch}" in
arm64 | aarch64) go_arch="arm64" ;;
x86_64) go_arch="amd64" ;;
*)
  echo "ERROR: unsupported architecture ${arch}" >&2
  exit 1
  ;;
esac

agw_bin="${tools_dir}/agentgateway"
if [[ ! -x ${agw_bin} ]]; then
  echo "Downloading agentgateway ${agentgateway_version} (${go_arch})..."
  curl -fsSL -o "${agw_bin}" \
    "https://github.com/christian-posta/agentgateway/releases/download/v${agentgateway_version}/agentgateway-darwin-${go_arch}"
  chmod +x "${agw_bin}"
  echo "  -> ${agw_bin}"
else
  echo "agentgateway already present."
fi

aauth_service_bin="${tools_dir}/aauth-service"
if [[ ! -x ${aauth_service_bin} ]]; then
  tarball="extauth-aauth-resource_${extauth_version}_darwin_${go_arch}.tar.gz"
  echo "Downloading extauth-aauth-resource ${extauth_version} (${go_arch})..."
  curl -fsSL -o "${tools_dir}/${tarball}" \
    "https://github.com/christian-posta/extauth-aauth-resource/releases/download/v${extauth_version}/${tarball}"
  extract_dir="${tools_dir}/extauth-extract"
  mkdir -p "${extract_dir}"
  tar -xzf "${tools_dir}/${tarball}" -C "${extract_dir}"
  found="$(find "${extract_dir}" -type f -name 'aauth-service' | head -1)"
  if [[ -z ${found} ]]; then
    echo "ERROR: aauth-service binary not found in ${tarball}; contents:" >&2
    find "${extract_dir}" -type f >&2
    exit 1
  fi
  mv "${found}" "${aauth_service_bin}"
  chmod +x "${aauth_service_bin}"
  rm -rf "${extract_dir:?}" "${tools_dir:?}/${tarball}"
  echo "  -> ${aauth_service_bin}"
else
  echo "aauth-service already present."
fi

for key in sca-resource-key maa-resource-key; do
  if [[ ! -f "${keys_dir}/${key}.pem" ]]; then
    openssl genpkey -algorithm ed25519 -out "${keys_dir}/${key}.pem"
    echo "Generated ${keys_dir}/${key}.pem"
  fi
done

echo "Edge toolchain ready. Run the demo with: scripts/run-demo.sh edge"
