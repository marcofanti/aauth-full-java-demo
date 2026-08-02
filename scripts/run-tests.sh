#!/usr/bin/env bash
# Runs the integration-test suites against live services, one AAuth mode at a time:
#   scripts/run-tests.sh [off|hwk|jwt|auth-token|consent|missions|all]     (default: all)
# For each mode: start services (run-demo.sh) -> run the mode's tagged tests -> stop.
# The Person Server is started on demand and left running between modes.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../hosts.env
source "${repo_root}/hosts.env"
requested="${1:-all}"

groups_for() {
  case "$1" in
  off) echo "core" ;;
  hwk) echo "core | signed" ;;
  jwt | auth-token) echo "core | signed | ps" ;;
  consent) echo "signed | ps | consent" ;;
  missions) echo "core | missions" ;;
  *)
    echo "Usage: $0 [off|hwk|jwt|auth-token|consent|missions|all]" >&2
    exit 1
    ;;
  esac
}

if [[ ${requested} == "all" ]]; then
  modes=(off hwk jwt auth-token consent missions)
else
  groups_for "${requested}" >/dev/null
  modes=("${requested}")
fi

echo "Building jars..."
(cd "${repo_root}" && mvn -q -DskipTests package)

declare -a results=()
overall=0
for mode in "${modes[@]}"; do
  echo
  echo "=================== mode: ${mode} ==================="
  "${repo_root}/scripts/stop-demo.sh" >/dev/null 2>&1 || true
  "${repo_root}/scripts/run-demo.sh" "${mode}"
  groups="$(groups_for "${mode}")"
  # -am builds sibling modules with unit tests skipped; the integration-tests module's own
  # explicit skipTests config (bound to skipITs) overrides the global -DskipTests.
  if (cd "${repo_root}" && mvn -pl integration-tests -am test -DskipTests -DskipITs=false \
    -Dit.groups="${groups}" -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddemo.portal.host="${DEMO_PORTAL_HOST}" -Ddemo.gateway.host="${DEMO_GATEWAY_HOST}" \
    -Ddemo.ps.host="${DEMO_PS_HOST}"); then
    results+=("${mode}: PASS")
  else
    results+=("${mode}: FAIL")
    overall=1
  fi
  "${repo_root}/scripts/stop-demo.sh"
done

echo
echo "=================== summary ==================="
for line in "${results[@]}"; do
  echo "  ${line}"
done
exit "${overall}"
