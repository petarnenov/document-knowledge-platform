#!/usr/bin/env bash
# Execute the actual command boundary, including each failure path, without Gradle/providers.
set -euo pipefail
subject="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/java-dm-check.sh"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/tools/ci"
cp -- "$subject" "$fixture/tools/ci/java-dm-check.sh"
cat > "$fixture/gradlew" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
[[ "$PWD" == "$CHECK_ROOT" ]]
printf '%s\n' "$*" >> "$CHECK_LOG"
if [[ "$*" == *"$CHECK_FAIL"* && "$CHECK_FAIL" != NONE ]]; then exit 23; fi
GRADLE
chmod +x "$fixture/gradlew"
export CHECK_ROOT="$fixture" CHECK_LOG="$fixture/commands" CHECK_FAIL=NONE
bash "$fixture/tools/ci/java-dm-check.sh"
printf '%s\n' '--no-daemon :services:document-management:bootstrap:build' '--no-daemon :services:document-management:adapters-out:persistenceCoverageVerification' > "$fixture/expected"
cmp "$CHECK_LOG" "$fixture/expected"
for CHECK_FAIL in bootstrap:build persistenceCoverageVerification; do
 export CHECK_FAIL
 : > "$CHECK_LOG"
 set +e
 bash "$fixture/tools/ci/java-dm-check.sh"
 result=$?
 set -e
 [[ "$result" == 23 ]]
 if [[ "$CHECK_FAIL" == bootstrap:build ]]; then [[ "$(wc -l < "$CHECK_LOG")" -eq 1 ]]; else cmp "$CHECK_LOG" "$fixture/expected"; fi
done
: > "$CHECK_LOG"
set +e
bash "$fixture/tools/ci/java-dm-check.sh" --skip-tests
result=$?
set -e
[[ "$result" == 64 && ! -s "$CHECK_LOG" ]]
printf '%s\n' 'JAVA_DM_COMMAND_BOUNDARY_PASS'
