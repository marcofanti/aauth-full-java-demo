#!/usr/bin/env bash
# One-time download of the tracing toolchain into tools/ (gitignored):
#   - OpenTelemetry Java agent (zero-code instrumentation for the three services)
#   - Jaeger v2 all-in-one binary (OTLP collector + UI on http://127.0.0.1:16686)
set -euo pipefail

otel_agent_version="2.30.0"
jaeger_version="2.20.0"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="${repo_root}/tools"
mkdir -p "${tools_dir}"

agent_jar="${tools_dir}/opentelemetry-javaagent.jar"
if [[ ! -f ${agent_jar} ]]; then
  echo "Downloading OpenTelemetry Java agent ${otel_agent_version}..."
  curl -fsSL -o "${agent_jar}" \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${otel_agent_version}/opentelemetry-javaagent.jar"
  echo "  -> ${agent_jar}"
else
  echo "OpenTelemetry Java agent already present."
fi

jaeger_bin="${tools_dir}/jaeger"
if [[ ! -x ${jaeger_bin} ]]; then
  arch="$(uname -m)"
  case "${arch}" in
  arm64 | aarch64) jaeger_arch="arm64" ;;
  x86_64) jaeger_arch="amd64" ;;
  *)
    echo "ERROR: unsupported architecture ${arch}" >&2
    exit 1
    ;;
  esac
  tarball="jaeger-${jaeger_version}-darwin-${jaeger_arch}.tar.gz"
  echo "Downloading Jaeger ${jaeger_version} (${jaeger_arch})..."
  curl -fsSL -o "${tools_dir}/${tarball}" \
    "https://github.com/jaegertracing/jaeger/releases/download/v${jaeger_version}/${tarball}"
  tar -xzf "${tools_dir}/${tarball}" -C "${tools_dir}" --strip-components=1 "jaeger-${jaeger_version}-darwin-${jaeger_arch}/jaeger"
  rm "${tools_dir}/${tarball}"
  echo "  -> ${jaeger_bin}"
else
  echo "Jaeger binary already present."
fi

echo "Tracing toolchain ready. Start the collector with scripts/run-jaeger.sh."
