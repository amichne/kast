#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
release="$repo_root/.github/workflows/release.yml"
runner="$repo_root/scripts/release/benchmark-real-repositories.sh"
cli_root="$repo_root/cli-rs/src/interface/cli/root.rs"
cli_config="$repo_root/cli-rs/src/interface/cli/workspace/config.rs"
dispatch="$repo_root/cli-rs/src/interface/entrypoint/dispatch.rs"
cli_model="$repo_root/cli-rs/src/configuration/config/model.rs"
idea_indexer="$repo_root/backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt"
indexer="$repo_root/index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt"
schema="$repo_root/index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/schema/SourceIndexSchemaTables.kt"

require() {
  local file="$1" text="$2" message="$3"
  grep -Fq -- "$text" "$file" || { printf 'error: %s\n' "$message" >&2; exit 1; }
}

reject() {
  local file="$1" text="$2" message="$3"
  ! grep -Fq -- "$text" "$file" || { printf 'error: %s\n' "$message" >&2; exit 1; }
}

[[ -x "$runner" ]] || {
  printf '%s\n' 'error: real-repository benchmark runner must be executable' >&2
  exit 1
}

require "$release" 'real-repository-indexing:' 'release workflow must own the real-repository indexing gate'
require "$release" 'fail-fast: false' 'repository matrix failures must not cancel remaining repositories'
require "$release" 'ktorio/ktor-samples.git' 'release gate must include a self-contained official Ktor sample'
reject "$release" 'ktorio/ktor.git' 'release gate must not use the Ktor included-build probe'
require "$release" 'AleksK1NG/Kotlin-Clean-Architecture-CQRS.git' 'release gate must include a Java 21 Kotlin Spring project'
reject "$release" 'spring-projects/spring-boot.git' 'release gate must not import the full Spring Boot monorepo'
require "$release" 'square/okhttp.git' 'release gate must include a Kotlin Multiplatform integration repository'
require "$release" 'graph_file: httpbin/src/main/kotlin/io/ktor/samples/httpbin/Server.kt' 'Ktor must use a pinned compiler graph probe'
require "$release" 'graph_file: src/main/kotlin/com/alexander/bryksin/kotlinspringcleanarchitecture/KotlinSpringCleanArchitectureApplication.kt' 'Spring must use a pinned compiler graph probe'
require "$release" 'graph_file: okcurl/src/main/kotlin/okhttp3/curl/Main.kt' 'OkHttp must use a pinned compiler graph probe'

relationship_setting() {
  local name="$1"
  awk -v name="$name" '
    $1 == "-" && $2 == "name:" {
      if (selected) exit
      selected = ($3 == name)
      next
    }
    selected && $1 == "relationships_enabled:" { print $2; exit }
  ' "$release"
}

for repository in ktor spring-boot okhttp; do
  [[ "$(relationship_setting "$repository")" == true ]] || {
    printf 'error: %s must complete relationship indexing for exact workspace cardinality\n' "$repository" >&2
    exit 1
  }
done
# shellcheck disable=SC2016 # GitHub expression is intentionally matched literally.
require "$release" 'linux-headless-tarball-${{ github.run_id }}' 'release gate must test the built release runtime'
require "$release" 'Set up Gradle Java 17 toolchain' 'release gate must install the Ktor sample toolchain'
require "$release" 'set-default: false' 'Gradle toolchain setup must not replace the Java 21 Kast runtime'
# shellcheck disable=SC2016 # GitHub expression is intentionally matched literally.
require "$release" 'GRADLE_JAVA_HOME: ${{ steps.gradle-java.outputs.path }}' 'release gate must bind the installed Gradle toolchain'
reject "$release" 'GRADLE_OPTS=' 'Gradle toolchain paths must not depend on a gradlew-only launcher variable'
require "$release" "--graph-file \"\${{ matrix.graph_file }}\"" 'release gate must pass the pinned compiler graph probe'
require "$release" "--relationships-enabled \"\${{ matrix.relationships_enabled }}\"" 'release gate must pass the relationship indexing plan'
require "$release" 'needs.real-repository-indexing.result == '\''success'\''' 'release publication must require the repository gate'
require "$runner" 'wait_timeout_ms=2700000' 'real repositories must have bounded Gradle import headroom'
require "$runner" 'kast config list' 'benchmark must capture effective workspace configuration'
require "$runner" "kast config set indexing.relationships.enabled \"\$relationships_enabled\"" 'benchmark must apply the declared relationship indexing plan'
require "$runner" "if [[ \"\$relationships_enabled\" == true ]]" 'relationship tuning must apply only when relationship indexing is enabled'
require "$runner" 'kast config set indexing.relationships.parallelism 2' 'benchmark must exercise relationship indexing configuration'
require "$runner" "cat \"\$scratch/runtime.json\" >&2" 'runtime readiness failures must preserve their typed evidence'
require "$runner" 'settings.gradle.kts' 'benchmark must recognize Kotlin Gradle build roots'
require "$runner" 'settings.gradle' 'benchmark must recognize Groovy Gradle build roots'
# shellcheck disable=SC2016 # Runner variables are intentionally matched literally.
require "$runner" 'scoped_graph_file="${graph_path#"$workspace"/}"' 'benchmark must make the probe relative to the selected Gradle root'
require "$runner" '--operation refresh' 'benchmark must populate the native graph through the compiler backend'
require "$runner" "--file-path \"\$scoped_graph_file\"" 'benchmark must refresh the pinned Kotlin source within the selected Gradle root'
require "$runner" '--exclusive' 'benchmark graph evidence must stay within the pinned probe scope'
require "$runner" 'Semantic graph refresh was incomplete' 'benchmark must verify compiler graph coverage'
reject "$runner" '--accept-indexing' 'benchmark must wait for the runtime to become ready'

reject_graph_file() {
  local candidate="$1" output
  if output="$(
    "$runner" \
      --name validation \
      --repository https://github.com/example/repository.git \
      --revision 0000000000000000000000000000000000000000 \
      --graph-file "$candidate" \
      --relationships-enabled false \
      --bundle /missing \
      --cache-root /unused 2>&1
  )"; then
    printf 'error: graph file was accepted: %s\n' "$candidate" >&2
    exit 1
  fi
  [[ "$output" == *'graph file must be a relative Kotlin path'* ]] || {
    printf 'error: graph file failed at the wrong boundary: %s: %s\n' "$candidate" "$output" >&2
    exit 1
  }
}

reject_graph_file ''
reject_graph_file /tmp/Probe.kt
reject_graph_file ./Probe.kt
reject_graph_file src//Probe.kt
reject_graph_file src/./Probe.kt
reject_graph_file src/../Probe.kt
reject_graph_file src/Probe.kts

# shellcheck disable=SC1090,SC1091 # The checked runner path is resolved above.
source "$runner"
scope_fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-scope.XXXXXX")"
trap 'rm -rf -- "$scope_fixture"' EXIT
repository_fixture="$scope_fixture/repository"
ktor_fixture="$repository_fixture/ktor-test-server"
mkdir -p "$ktor_fixture/src/main/kotlin/test/server" "$repository_fixture/okcurl/src/main/kotlin"
touch \
  "$repository_fixture/settings.gradle.kts" \
  "$ktor_fixture/settings.gradle.kts" \
  "$ktor_fixture/src/main/kotlin/test/server/ServerUtils.kt" \
  "$repository_fixture/okcurl/src/main/kotlin/Main.kt"
[[ "$(gradle_workspace_for "$ktor_fixture/src/main/kotlin/test/server/ServerUtils.kt" "$repository_fixture")" == "$ktor_fixture" ]] \
  || { printf 'error: nested Ktor build was not selected\n' >&2; exit 1; }
[[ "$(gradle_workspace_for "$repository_fixture/okcurl/src/main/kotlin/Main.kt" "$repository_fixture")" == "$repository_fixture" ]] \
  || { printf 'error: repository Gradle root was not selected\n' >&2; exit 1; }
mkdir -p "$scope_fixture/no-settings/src"
touch "$scope_fixture/no-settings/src/Probe.kt"
if gradle_workspace_for "$scope_fixture/no-settings/src/Probe.kt" "$scope_fixture/no-settings" >/dev/null; then
  printf 'error: probe outside a Gradle build was accepted\n' >&2
  exit 1
fi
gradle_user_fixture="$scope_fixture/gradle-user"
gradle_java_fixture="$scope_fixture/java-17"
runtime_java_fixture="$scope_fixture/java-21"
mkdir -p "$gradle_user_fixture" "$gradle_java_fixture" "$runtime_java_fixture"
configure_gradle_java_paths "$gradle_user_fixture" "$gradle_java_fixture" "$runtime_java_fixture"
[[ "$(cat "$gradle_user_fixture/gradle.properties")" == \
    "org.gradle.java.installations.paths=$gradle_java_fixture,$runtime_java_fixture" ]] \
  || { printf 'error: Gradle Tooling API paths were not configured\n' >&2; exit 1; }
if configure_gradle_java_paths "$gradle_user_fixture" "$scope_fixture/missing-java" "$runtime_java_fixture" 2>/dev/null; then
  printf 'error: missing Gradle Java home was accepted\n' >&2
  exit 1
fi

require "$cli_root" 'Config(ConfigArgs)' 'CLI must expose the config command family'
require "$cli_config" 'List(ConfigWorkspaceArgs)' 'config must list effective workspace state'
require "$cli_config" 'Set(ConfigSetArgs)' 'config must set one workspace field non-interactively'
require "$cli_config" 'Unset(ConfigUnsetArgs)' 'config must unset one workspace field non-interactively'
require "$dispatch" 'Command::Config(args)' 'config commands must be dispatched'
require "$cli_model" 'pub relationships: RelationshipIndexingConfig' 'effective config must name relationship indexing directly'
require "$idea_indexer" 'indexSymbolRelationships(' 'IDEA orchestration must name the compiler-resolved operation'
require "$indexer" 'onFilesIndexed(successfulPaths)' 'failed scans must not be reported as indexed'
require "$schema" 'relationship_index_status TEXT NOT NULL' 'module progress must name relationship indexing state'

printf '%s\n' 'release indexing benchmark contract passed'
