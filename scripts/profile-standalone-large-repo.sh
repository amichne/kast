#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/profile-standalone-large-repo.sh [options]

Profiles the kast standalone backend in Docker against a large Gradle workspace.

Targets:
  --target ktor              Use ktorio/ktor tag 3.2.3 (default; 96%+ Kotlin).
  --target opensearch        Use opensearch-project/OpenSearch tag 3.3.0.
  --target synthetic-kotlin  Generate a Gradle 8.14.3 / Java 21 Kotlin megarepo.
  --workspace-dir PATH       Use an existing local workspace instead of cloning/generating.
  --repo-url URL             Clone a custom Git repository.
  --repo-ref REF             Git branch, tag, or ref for --repo-url.

Profiling options:
  --duration SECONDS         async-profiler duration per mode (default: 45).
  --profile-modes LIST       Comma list: cpu,wall,alloc,lock (default: wall,cpu).
  --heap SIZE                JVM heap for kast standalone (default: 8g).
  --max-concurrent N         Backend max concurrent requests (default: 4).
  --tooling-timeout-ms MS    Gradle Tooling API timeout in kast config (default: 300000).
  --max-included-projects N  Static-discovery threshold in kast config (default: 200).
  --telemetry-detail LEVEL   Telemetry detail: basic or verbose (default: verbose).
  --ready-timeout-seconds N  Seconds to wait for health/READY in the workload (default: 600).
  --include-refresh          Include a full workspace refresh in the workload.
  --gradle-jvmargs ARGS      Gradle daemon JVM args inside Docker (default: -Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8).
  --kotlin-daemon-jvmargs ARGS
                              Kotlin daemon JVM args inside Docker (default: -Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8).
  --gradle-workers-max N     Gradle max workers inside Docker (default: --max-concurrent).

Synthetic target options:
  --modules N                Synthetic module count (default: 240).
  --fanout N                 Synthetic project dependency fanout (default: 3).
  --classes-per-module N     Kotlin classes per synthetic module (default: 3).
  --regenerate-synthetic     Recreate an existing synthetic target owned by this script.

Build/run options:
  --work-dir PATH            Benchmark state directory (default: .benchmarks/standalone-profile).
  --image NAME               Docker image tag (default: kast-standalone-profile:local).
  --docker-memory SIZE       Optional Docker memory limit, e.g. 12g or 8192m.
  --run-id ID                Result directory name.
  --rebuild-backend          Run ./kast.sh build backend even when dist/backend exists.
  --skip-backend-build       Require an existing dist/backend tree.
  --skip-docker-build        Reuse the Docker image.
  --dry-run                  Print the planned commands without running them.
  -h, --help                 Show this help.
USAGE
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '> %s\n' "$*" >&2
}

quote_cmd() {
  local arg
  printf '%q' "$1"
  shift || true
  for arg in "$@"; do
    printf ' %q' "$arg"
  done
  printf '\n'
}

run_cmd() {
  if [[ "${dry_run}" == "true" ]]; then
    printf '[dry-run] '
    quote_cmd "$@"
    return 0
  fi
  "$@"
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"
}

ensure_docker_daemon() {
  if [[ "${dry_run}" == "true" ]]; then
    return 0
  fi
  docker info >/dev/null 2>&1 || die "Docker is installed, but the daemon is not reachable. Start Docker and rerun this script."
}

sanitize_label() {
  printf '%s' "$1" | tr -c '[:alnum:]._-:' '-'
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"

target="ktor"
workspace_dir=""
repo_url=""
repo_ref=""
duration="45"
profile_modes="wall,cpu"
heap="8g"
max_concurrent="4"
tooling_timeout_ms="300000"
max_included_projects="200"
telemetry_detail="verbose"
ready_timeout_seconds="600"
include_refresh="false"
gradle_jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8"
kotlin_daemon_jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
gradle_workers_max=""
modules="240"
fanout="3"
classes_per_module="3"
regenerate_synthetic="false"
work_dir=".benchmarks/standalone-profile"
image="kast-standalone-profile:local"
docker_memory=""
run_id=""
rebuild_backend="false"
skip_backend_build="false"
skip_docker_build="false"
dry_run="false"
asprof_version="4.4"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target) target="${2:?missing value for --target}"; shift 2 ;;
    --workspace-dir) workspace_dir="${2:?missing value for --workspace-dir}"; shift 2 ;;
    --repo-url) repo_url="${2:?missing value for --repo-url}"; shift 2 ;;
    --repo-ref) repo_ref="${2:?missing value for --repo-ref}"; shift 2 ;;
    --duration) duration="${2:?missing value for --duration}"; shift 2 ;;
    --profile-modes) profile_modes="${2:?missing value for --profile-modes}"; shift 2 ;;
    --heap) heap="${2:?missing value for --heap}"; shift 2 ;;
    --max-concurrent) max_concurrent="${2:?missing value for --max-concurrent}"; shift 2 ;;
    --tooling-timeout-ms) tooling_timeout_ms="${2:?missing value for --tooling-timeout-ms}"; shift 2 ;;
    --max-included-projects) max_included_projects="${2:?missing value for --max-included-projects}"; shift 2 ;;
    --telemetry-detail) telemetry_detail="${2:?missing value for --telemetry-detail}"; shift 2 ;;
    --ready-timeout-seconds) ready_timeout_seconds="${2:?missing value for --ready-timeout-seconds}"; shift 2 ;;
    --include-refresh) include_refresh="true"; shift ;;
    --gradle-jvmargs) gradle_jvmargs="${2:?missing value for --gradle-jvmargs}"; shift 2 ;;
    --kotlin-daemon-jvmargs) kotlin_daemon_jvmargs="${2:?missing value for --kotlin-daemon-jvmargs}"; shift 2 ;;
    --gradle-workers-max) gradle_workers_max="${2:?missing value for --gradle-workers-max}"; shift 2 ;;
    --modules) modules="${2:?missing value for --modules}"; shift 2 ;;
    --fanout) fanout="${2:?missing value for --fanout}"; shift 2 ;;
    --classes-per-module) classes_per_module="${2:?missing value for --classes-per-module}"; shift 2 ;;
    --regenerate-synthetic) regenerate_synthetic="true"; shift ;;
    --work-dir) work_dir="${2:?missing value for --work-dir}"; shift 2 ;;
    --image) image="${2:?missing value for --image}"; shift 2 ;;
    --docker-memory) docker_memory="${2:?missing value for --docker-memory}"; shift 2 ;;
    --run-id) run_id="${2:?missing value for --run-id}"; shift 2 ;;
    --rebuild-backend) rebuild_backend="true"; shift ;;
    --skip-backend-build) skip_backend_build="true"; shift ;;
    --skip-docker-build) skip_docker_build="true"; shift ;;
    --dry-run) dry_run="true"; shift ;;
    --asprof-version) asprof_version="${2:?missing value for --asprof-version}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

case "${target}" in
  ktor|opensearch|synthetic-kotlin|custom) ;;
  *) die "--target must be one of: ktor, opensearch, synthetic-kotlin, custom" ;;
esac

[[ "${duration}" =~ ^[0-9]+$ ]] || die "--duration must be an integer"
[[ "${modules}" =~ ^[0-9]+$ ]] || die "--modules must be an integer"
[[ "${fanout}" =~ ^[0-9]+$ ]] || die "--fanout must be an integer"
[[ "${classes_per_module}" =~ ^[0-9]+$ ]] || die "--classes-per-module must be an integer"
[[ "${max_concurrent}" =~ ^[0-9]+$ ]] || die "--max-concurrent must be an integer"
[[ "${tooling_timeout_ms}" =~ ^[0-9]+$ ]] || die "--tooling-timeout-ms must be an integer"
[[ "${max_included_projects}" =~ ^[0-9]+$ ]] || die "--max-included-projects must be an integer"
[[ "${ready_timeout_seconds}" =~ ^[0-9]+$ ]] || die "--ready-timeout-seconds must be an integer"
gradle_workers_max="${gradle_workers_max:-${max_concurrent}}"
[[ "${gradle_workers_max}" =~ ^[0-9]+$ ]] || die "--gradle-workers-max must be an integer"
case "${telemetry_detail}" in
  basic|verbose) ;;
  *) die "--telemetry-detail must be one of: basic, verbose" ;;
esac

need_tool docker
need_tool git
need_tool python3
ensure_docker_daemon

work_dir_abs="$(cd "${repo_root}" && mkdir -p "${work_dir}" && cd "${work_dir}" && pwd)"
repos_dir="${work_dir_abs}/repos"
results_root="${work_dir_abs}/results"
gradle_home="${work_dir_abs}/gradle-home"
backend_dir="${repo_root}/dist/backend"
dockerfile="${repo_root}/scripts/profiling/Dockerfile.standalone-profile"
target_label="${target}"

prepare_opensearch() {
  repo_url="https://github.com/opensearch-project/OpenSearch.git"
  repo_ref="3.3.0"
  workspace_dir="${repos_dir}/opensearch-3.3.0"
  target_label="opensearch-3.3.0"
}

prepare_ktor() {
  repo_url="https://github.com/ktorio/ktor.git"
  repo_ref="3.2.3"
  workspace_dir="${repos_dir}/ktor-3.2.3"
  target_label="ktor-3.2.3"
}

prepare_custom_repo() {
  [[ -n "${repo_url}" ]] || die "--repo-url is required for --target custom when --workspace-dir is not set"
  [[ -n "${repo_ref}" ]] || repo_ref="main"
  local repo_name
  repo_name="$(basename "${repo_url}" .git)"
  workspace_dir="${repos_dir}/$(sanitize_label "${repo_name}-${repo_ref}")"
  target_label="$(sanitize_label "${repo_name}-${repo_ref}")"
}

clone_repo() {
  local url="$1"
  local ref="$2"
  local dest="$3"
  mkdir -p "$(dirname "${dest}")"
  if [[ -d "${dest}/.git" ]]; then
    log "Refreshing ${dest} at ${ref}"
    run_cmd git -C "${dest}" fetch --depth 1 origin "${ref}"
    run_cmd git -C "${dest}" checkout --detach FETCH_HEAD
  else
    log "Cloning ${url} (${ref}) into ${dest}"
    run_cmd git clone --depth 1 --branch "${ref}" --filter=blob:none "${url}" "${dest}"
  fi
}

copy_wrapper_into_synthetic_repo() {
  local dest="$1"
  run_cmd mkdir -p "${dest}/gradle/wrapper"
  run_cmd cp "${repo_root}/gradlew" "${dest}/gradlew"
  run_cmd cp "${repo_root}/gradlew.bat" "${dest}/gradlew.bat"
  run_cmd cp "${repo_root}/gradle/wrapper/gradle-wrapper.jar" "${dest}/gradle/wrapper/gradle-wrapper.jar"
  run_cmd chmod +x "${dest}/gradlew"
}

prepare_synthetic() {
  workspace_dir="${repos_dir}/synthetic-kotlin-${modules}-modules"
  target_label="synthetic-kotlin-${modules}-modules"
  local generator_args=(
    "${repo_root}/scripts/profiling/generate-kotlin-megarepo.py"
    --output "${workspace_dir}"
    --modules "${modules}"
    --fanout "${fanout}"
    --classes-per-module "${classes_per_module}"
  )
  if [[ "${regenerate_synthetic}" == "true" ]]; then
    generator_args+=(--force)
  fi
  log "Preparing synthetic Kotlin workspace at ${workspace_dir}"
  run_cmd python3 "${generator_args[@]}"
  copy_wrapper_into_synthetic_repo "${workspace_dir}"
}

if [[ -n "${workspace_dir}" ]]; then
  workspace_dir="$(cd "${repo_root}" && mkdir -p "$(dirname "${workspace_dir}")" && cd "$(dirname "${workspace_dir}")" && pwd)/$(basename "${workspace_dir}")"
  target_label="$(sanitize_label "$(basename "${workspace_dir}")")"
else
  case "${target}" in
    ktor) prepare_ktor ;;
    opensearch) prepare_opensearch ;;
    synthetic-kotlin) prepare_synthetic ;;
    custom) prepare_custom_repo ;;
  esac
fi

if [[ -n "${repo_url}" && "${target}" != "synthetic-kotlin" && ! -d "${workspace_dir}" ]]; then
  clone_repo "${repo_url}" "${repo_ref}" "${workspace_dir}"
elif [[ -n "${repo_url}" && "${target}" != "synthetic-kotlin" ]]; then
  clone_repo "${repo_url}" "${repo_ref}" "${workspace_dir}"
fi

if [[ "${dry_run}" != "true" ]]; then
  [[ -d "${workspace_dir}" ]] || die "workspace does not exist: ${workspace_dir}"
fi

if [[ "${skip_backend_build}" != "true" ]]; then
  if [[ "${rebuild_backend}" == "true" || ! -x "${backend_dir}/kast-standalone" ]]; then
    log "Building standalone backend distribution"
    run_cmd "${repo_root}/kast.sh" build backend
  fi
fi

if [[ "${dry_run}" != "true" && ! -x "${backend_dir}/kast-standalone" ]]; then
  die "missing backend launcher at ${backend_dir}/kast-standalone; run ./kast.sh build backend"
fi

if [[ "${skip_docker_build}" != "true" ]]; then
  log "Building Docker image ${image}"
  run_cmd docker build \
    --build-arg "ASPROF_VERSION=${asprof_version}" \
    -f "${dockerfile}" \
    -t "${image}" \
    "${repo_root}"
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_id="${run_id:-${timestamp}-${target_label}}"
results_dir="${results_root}/${run_id}"

run_cmd mkdir -p "${results_dir}" "${gradle_home}"

docker_args=(
  run
  --rm
  --init
  --cap-add SYS_PTRACE
  --security-opt seccomp=unconfined
  --user "$(id -u):$(id -g)"
)

if [[ -n "${docker_memory}" ]]; then
  docker_args+=(--memory "${docker_memory}")
fi

docker_args+=(
  -e "HOME=/work/results/home"
  -e "GRADLE_USER_HOME=/work/gradle-home"
  -e "KAST_PROFILE_DURATION=${duration}"
  -e "KAST_PROFILE_MODES=${profile_modes}"
  -e "KAST_PROFILE_HEAP=${heap}"
  -e "KAST_MAX_CONCURRENT_REQUESTS=${max_concurrent}"
  -e "KAST_PROFILE_TOOLING_TIMEOUT_MS=${tooling_timeout_ms}"
  -e "KAST_PROFILE_MAX_INCLUDED_PROJECTS=${max_included_projects}"
  -e "KAST_PROFILE_TELEMETRY_DETAIL=${telemetry_detail}"
  -e "KAST_PROFILE_READY_TIMEOUT_SECONDS=${ready_timeout_seconds}"
  -e "KAST_PROFILE_INCLUDE_REFRESH=${include_refresh}"
  -e "KAST_PROFILE_GRADLE_JVMARGS=${gradle_jvmargs}"
  -e "KAST_PROFILE_KOTLIN_DAEMON_JVMARGS=${kotlin_daemon_jvmargs}"
  -e "KAST_PROFILE_GRADLE_WORKERS_MAX=${gradle_workers_max}"
  -e "KAST_PROFILE_TARGET_LABEL=${target_label}"
  -v "${backend_dir}:/opt/kast/backend:ro"
  -v "${workspace_dir}:/work/target:rw"
  -v "${results_dir}:/work/results:rw"
  -v "${gradle_home}:/work/gradle-home:rw"
  "${image}"
)

log "Running profiling session ${run_id}"
run_cmd docker "${docker_args[@]}"

if [[ "${dry_run}" != "true" ]]; then
  log "Results written to ${results_dir}"
  printf '%s\n' "${results_dir}"
fi
