#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" \
    || die "${description}: missing '${expected}' in ${file_path}"
}

require_count() {
  local file_path="$1"
  local expected="$2"
  local required_count="$3"
  local description="$4"
  local actual_count
  actual_count="$(grep -Fc -- "$expected" "$file_path")"
  [[ "$actual_count" -eq "$required_count" ]] \
    || die "${description}: expected ${required_count} occurrences of '${expected}' in ${file_path}, found ${actual_count}"
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
root_build="${repo_root}/build.gradle.kts"
indexer_build="${repo_root}/indexer/build.gradle.kts"
runtime_app_plugin="${repo_root}/build-logic/src/main/kotlin/kast.runtime-app.gradle.kts"
verify_layout_task="${repo_root}/build-logic/src/main/kotlin/support/tasks/VerifyClasspathLayoutTask.kt"
api_spec="${repo_root}/cli-rs/protocol/api-specification.md"

for path in \
  "$root_build" \
  "$indexer_build" \
  "$runtime_app_plugin" \
  "$verify_layout_task" \
  "$api_spec"; do
  [[ -f "$path" ]] || die "required build contract file is missing: $path"
done

require_contains "$root_build" 'tasks.register("stageIndexerDist")' \
  "root build must expose the indexer staging task"
require_contains "$root_build" 'tasks.register("buildIndexerPortableZip")' \
  "root build must expose the portable indexer zip task"
require_contains "$root_build" 'dependsOn(":indexer:portableDistZip")' \
  "root portable packaging must delegate to the indexer module"

require_contains "$runtime_app_plugin" 'val archiveRoot = applicationName' \
  "shared runtime packaging must derive the archive root from the module name"
require_contains "$runtime_app_plugin" 'archiveBaseName.set(applicationName)' \
  "shared runtime packaging must derive the archive name from the module name"
require_contains "$runtime_app_plugin" 'kastIncludeShadowJar' \
  "shared runtime packaging must expose explicit fat-jar inclusion policy"

require_contains "$indexer_build" 'extra["kastIncludeShadowJar"] = "false"' \
  "indexer must exclude the shadow fat jar"
require_contains "$indexer_build" 'archiveClassifier.set("launcher")' \
  "indexer must classify its launcher jar"
require_contains "$indexer_build" 'archiveClassifier.set("plugin")' \
  "indexer must classify its private payload jar"
require_contains "$indexer_build" 'outputFile.set(layout.buildDirectory.file("scripts/kast-indexer"))' \
  "indexer must expose one launcher"
require_count "$indexer_build" 'into("idea-home/plugins/kast-indexer/lib")' 2 \
  "both indexer payload copies must use the private runtime directory"
require_contains "$indexer_build" 'it.dir("idea-home/plugins/kast-indexer/lib")' \
  "indexer layout verification must require its private payload directory"
require_contains "$indexer_build" 'relativePath.pathString == "indexer/kast-indexer"' \
  "portable archive verification must require the indexer executable"

require_contains "$verify_layout_task" 'forbiddenPortableDistJarSuffixes' \
  "portable layout verifier must reject forbidden jars"
require_contains "$api_spec" './gradlew stageOpenApiSpec' \
  "protocol guidance must use the native Gradle distribution task"

printf '%s\n' "Kast build contract passed"
