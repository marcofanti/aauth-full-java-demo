#!/usr/bin/env bash
# One-time local TLS setup for the *.uma.lab HTTPS front (caddy/Caddyfile):
#   - an mkcert certificate for *.uma.lab, shared with aauth-java-person-server
#   - a JVM truststore = the JDK's default CAs + mkcert's root, so the Java services trust
#     https://*.uma.lab without modifying the JDK's own cacerts
# Requires mkcert with its root installed (`mkcert -install`). Idempotent.
set -euo pipefail

tls_dir="${HOME}/.local/share/uma-lab-tls"
mkdir -p "${tls_dir}"

if [[ ! -f "${tls_dir}/cert.pem" ]]; then
  (cd "${tls_dir}" && mkcert -cert-file cert.pem -key-file key.pem "*.uma.lab" uma.lab)
fi

truststore="${tls_dir}/truststore.p12"
if [[ ! -f ${truststore} ]]; then
  java_home="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.home/ {print $2}')"
  cp "${java_home}/lib/security/cacerts" "${truststore}"
  keytool -importcert -noprompt -keystore "${truststore}" -storepass changeit \
    -alias mkcert-root -file "$(mkcert -CAROOT)/rootCA.pem" >/dev/null
fi
echo "TLS ready: ${tls_dir} (cert, key, JVM truststore)"
