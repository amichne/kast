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

repo_root="$(resolve_repo_root)"
kast_script="${repo_root}/kast.sh"
headless_build="${repo_root}/backend-headless/build.gradle.kts"
headless_project_opener="${repo_root}/backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessProjectOpener.kt"
runtime_app_plugin="${repo_root}/build-logic/src/main/kotlin/kast.runtime-app.gradle.kts"
verify_layout_task="${repo_root}/build-logic/src/main/kotlin/VerifyClasspathLayoutTask.kt"

for path in "$kast_script" "$headless_build" "$headless_project_opener" "$runtime_app_plugin" "$verify_layout_task"; do
  [[ -f "$path" ]] || die "Required build contract file is missing: $path"
done

require_contains "$runtime_app_plugin" "kastIncludeShadowJar" "Shared app packaging must expose a shadow-jar inclusion property"
require_contains "$headless_build" 'extra["kastIncludeShadowJar"] = "false"' "Headless backend must opt out of the shadow fat jar"
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

require_contains "$kast_script" "full|minimal|agent" "kast.sh must accept the agent headless IDEA-home profile"
require_contains "$kast_script" "--headless-idea-home-profile=full|minimal|agent" "kast.sh help must document the agent profile"
require_contains "$kast_script" "must not include fat jars" "kast.sh must reject staged headless fat jars"

printf '%s\n' "Kast build contract passed"
