#!/usr/bin/env bash
# Exact shared local/CI DM checks; the launcher supplies the pinned Linux JDK and Docker.
set -euo pipefail
if [[ $# -ne 0 ]]; then exit 64; fi
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
./gradlew --no-daemon :services:document-management:bootstrap:build
./gradlew --no-daemon :services:document-management:adapters-out:persistenceCoverageVerification
