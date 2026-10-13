#!/usr/bin/env bash
# Approves first-use person-token consents (AAuth draft-11 §7.1) as they appear, standing in
# for the person in a demo session. Only pendings of kind "person_token" are approved; the
# require:user consent on an auth-token exchange (consent mode) is left for a human.
#   scripts/approve-person-tokens.sh <person-server-origin> [admin-token]
# Started in the background by run-demo.sh and stopped by stop-demo.sh.
set -euo pipefail

ps_origin="${1:?usage: $0 <person-server-origin> [admin-token]}"
admin_token="${2:-mytoken}"

while true; do
  curl -sf -H "Authorization: Bearer ${admin_token}" "${ps_origin}/admin/pending" 2>/dev/null |
    python3 -c '
import json, sys
for row in json.load(sys.stdin):
    if row.get("kind") == "person_token":
        print(row["pending_id"])
' 2>/dev/null |
    while read -r pending_id; do
      if curl -sf -o /dev/null -X POST -H "Content-Type: application/json" -d '{"approved": true}' \
        "${ps_origin}/consent/${pending_id}/decision"; then
        echo "Approved person-token request ${pending_id}"
      fi
    done
  sleep 1
done
