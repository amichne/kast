#!/usr/bin/env bash
# Portable example checks only. This is not the Gradle gate or installed-system acceptance.
set -euo pipefail
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v kotlinc >/dev/null
command -v java >/dev/null
mkdir -p build/local
kotlinc -version
java -version
kotlinc model/src/main/kotlin/*.kt -d build/local/model.jar
kotlinc -cp build/local/model.jar read/src/main/kotlin/*.kt -d build/local/read.jar
kotlinc -cp build/local/model.jar:build/local/read.jar coordinator/src/main/kotlin/*.kt -d build/local/coordinator.jar
kotlinc -cp build/local/model.jar network/src/main/kotlin/*.kt -d build/local/network.jar
classpath=build/local/model.jar:build/local/read.jar:build/local/coordinator.jar:build/local/network.jar
kotlinc -cp "$classpath" program/*.kt verification/src/main/kotlin/*.kt -include-runtime -d build/local/verification.jar
for suite in policy readiness trust graph; do
    java -cp "build/local/verification.jar:$classpath" kast.baseline.verification.MainKt "$suite"
done
for projection in projection program-schema receipt-schema; do
    java -cp "build/local/verification.jar:$classpath" kast.baseline.verification.MainKt "$projection" > "build/local/$projection.json"
done
cat > build/local/Forbidden.kt <<'KOTLIN'
import kast.baseline.coordinator.StartCoordinator
fun forbidden(value: StartCoordinator) = value
KOTLIN
if kotlinc -cp build/local/model.jar:build/local/read.jar build/local/Forbidden.kt -d build/local/forbidden.jar > build/local/forbidden.log 2>&1; then
    echo "FAIL read module obtained preparation authority" >&2
    exit 1
fi
grep -q 'unresolved reference:.*coordinator\|unresolved reference:.*StartCoordinator' build/local/forbidden.log
printf '%s\n' 'PASS read-compilation-cannot-obtain-preparation-authority'
