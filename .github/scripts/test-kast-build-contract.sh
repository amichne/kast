#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
}

require_not_contains() {
  local file_path="$1"
  local forbidden="$2"
  local description="$3"
  ! grep -Fq -- "$forbidden" "$file_path" || die "${description}: found forbidden '${forbidden}' in ${file_path}"
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

repo_root="$(resolve_repo_root)"
root_build="${repo_root}/build.gradle.kts"
headless_build="${repo_root}/backend-headless/build.gradle.kts"
idea_build="${repo_root}/backend-idea/build.gradle.kts"
headless_project_opener="${repo_root}/backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessProjectOpener.kt"
runtime_app_plugin="${repo_root}/build-logic/src/main/kotlin/kast.runtime-app.gradle.kts"
verify_layout_task="${repo_root}/build-logic/src/main/kotlin/VerifyClasspathLayoutTask.kt"
api_spec="${repo_root}/cli-rs/protocol/api-specification.md"

for path in "$root_build" "$headless_build" "$idea_build" "$headless_project_opener" "$runtime_app_plugin" "$verify_layout_task" "$api_spec"; do
  [[ -f "$path" ]] || die "Required build contract file is missing: $path"
done

require_contains "$root_build" 'tasks.register("stageHeadlessDist")' "Root Gradle build must expose the headless staging task"
require_contains "$root_build" 'tasks.register("buildHeadlessPortableZip")' "Root Gradle build must expose the portable zip task"
require_contains "$runtime_app_plugin" "kastIncludeShadowJar" "Shared app packaging must expose a shadow-jar inclusion property"
require_contains "$headless_build" 'extra["kastIncludeShadowJar"] = "false"' "Headless backend must opt out of the shadow fat jar"
require_contains "$idea_build" "val headlessRuntimeElements: Configuration" "IDEA must publish a typed headless-only runtime variant"
require_contains "$idea_build" 'outgoing.artifact(tasks.named<Jar>("jar"))' "The headless-only IDEA variant must publish only the base jar"
require_contains "$idea_build" 'outgoing.capability("${project.group}:backend-idea-headless-runtime:${project.version}")' "The headless-only IDEA variant must expose a distinct capability"
require_count "$headless_build" "requireCapability(backendIdeaHeadlessRuntimeCapability)" 3 "Every headless runtime consumer must select the base-only IDEA capability"
require_contains "$headless_build" "agentPackagedIdeaHomeEntries" "Headless backend must define the agent IDEA-home profile"
require_contains "$headless_build" '"agent" -> agentPackagedIdeaHomeEntries' "Headless backend must select the agent IDEA-home profile"
require_contains "$headless_build" '"plugins/java-ide-customization/**"' "Agent profile must include the Java IDE customization plugin required by IDEA startup"
require_contains "$headless_build" '"plugins/json/**"' "Agent profile must include the JSON plugin required by IDEA startup"
require_contains "$headless_build" '"plugins/maven/**"' "Agent profile must include Maven plugin support for Gradle dependency import"
require_contains "$headless_build" '"plugins/repository-search/**"' "Agent profile must include repository search support for dependency metadata"
require_contains "$headless_build" '"plugins/toml/**"' "Agent profile must include TOML support for version catalogs"
require_contains "$headless_build" '"plugins/yaml/**"' "Agent profile must include YAML support for common project configuration"
require_not_contains "$headless_project_opener" "waitForSmartMode()" "Headless startup must not block the application starter before the analysis server registers"
require_contains "$verify_layout_task" "forbiddenPortableDistJarSuffixes" "Headless layout verifier must reject forbidden portable-dist jars"
require_contains "$api_spec" "./gradlew stageOpenApiSpec" "Protocol guidance must use the native Gradle distribution task"
require_not_contains "$api_spec" "./kast.sh" "Protocol guidance must not reference the deleted build wrapper"

printf '%s\n' "Kast build contract passed"
