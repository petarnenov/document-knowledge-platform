#!/usr/bin/env bash
# #222 affected component profile, shared by local Linux containers and CI.
set -euo pipefail
if [[ $# -ne 0 || $(uname -s) != Linux ]]; then exit 64; fi
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
java -version
java -version 2>&1 | head -n 1 | grep -F '"25.0.5"'
# Serialize separate project test JVMs inside the unchanged 256-PID verification budget.
# The named component cases still exercise their original concurrent requests/readers.
./gradlew --offline --no-daemon --max-workers=1 --rerun-tasks \
  :services:browser-bff:bootstrap:build \
  :services:browser-bff:bootstrap:ingressLinuxAcceptance
