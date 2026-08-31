#!/usr/bin/env bash
set -euo pipefail

launcher="${1:?usage: test-kast-indexer-isolation.sh LAUNCHER}"
[[ -f "${launcher}" ]] || {
  echo "indexer-launcher-isolation-test: launcher is missing: ${launcher}" >&2
  exit 1
}

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-indexer-isolation.XXXXXX")"
fixture="$(cd -P -- "${fixture}" && pwd -P)"
cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT

installed="${fixture}/installed"
idea_home="${fixture}/IntelliJ IDEA.app/Contents"
java_executable="${idea_home}/jbr/Contents/Home/bin/java"
workspace="${fixture}/workspace"
private_plugins="${installed}/private-plugins"
mkdir -p \
  "${installed}/runtime-libs" \
  "${idea_home}/lib/jna/aarch64" \
  "${idea_home}/lib/pty4j" \
  "${idea_home}/modules" \
  "$(dirname "${java_executable}")" \
  "${private_plugins}/kast-indexer/lib" \
  "${workspace}"
cp "${launcher}" "${installed}/kast-indexer"
chmod 755 "${installed}/kast-indexer"
printf '%s\n' 'launcher.jar' >"${installed}/runtime-libs/classpath.txt"
printf '%s\n' 'launcher' >"${installed}/runtime-libs/launcher.jar"
mkdir -p "${idea_home}/Resources"
printf '%s\n' '{}' >"${idea_home}/Resources/product-info.json"
printf '%s\n' 'nio' >"${idea_home}/lib/nio-fs.jar"
printf '%s\n' 'modules' >"${idea_home}/modules/module-descriptors.dat"
printf '%s\n' 'plugin' >"${private_plugins}/kast-indexer/lib/indexer-plugin.jar"

cat >"${java_executable}" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" >"${CAPTURE_FILE:?CAPTURE_FILE is required}"
FAKE_JAVA
chmod 755 "${java_executable}"

snapshot_tree() {
  local root="$1"
  (
    cd "${root}"
    find . -type d -print
    find . -type f -print0 | sort -z | xargs -0 shasum -a 256
  ) | shasum -a 256
}

installed_before="$(snapshot_tree "${installed}")"
idea_before="$(snapshot_tree "${idea_home}")"

runtime_id="sha256:$(printf 'c%.0s' {1..64})"
socket_a="${fixture}/runtime/kast-aaaaaaaaaaaaaaaaaaaaaaaa.sock"
socket_b="${fixture}/runtime/kast-bbbbbbbbbbbbbbbbbbbbbbbb.sock"
cache_a="${fixture}/cache-a"
cache_b="${fixture}/cache-b"
mkdir -p \
  "${cache_a}/system" "${cache_a}/config" "${cache_a}/log" \
  "${cache_b}/system" "${cache_b}/config" "${cache_b}/log"

run_launcher() {
  local socket="$1"
  local cache="$2"
  local capture="$3"
  CAPTURE_FILE="${capture}" \
    "${installed}/kast-indexer" \
      --workspace-root="${workspace}" \
      --socket-path="${socket}" \
      --runtime-id="${runtime_id}" \
      --idea-home="${idea_home}" \
      --java-executable="${java_executable}" \
      --idea-system-path="${cache}/system" \
      --idea-config-path="${cache}/config" \
      --idea-log-path="${cache}/log" \
      --private-plugins-path="${private_plugins}" \
      --cache-state-path="${cache}/cache-state"
}

capture_a="${fixture}/capture-a"
capture_a_restart="${fixture}/capture-a-restart"
capture_b="${fixture}/capture-b"
run_launcher "${socket_a}" "${cache_a}" "${capture_a}"
run_launcher "${socket_a}" "${cache_a}" "${capture_a_restart}"
run_launcher "${socket_b}" "${cache_b}" "${capture_b}"

[[ "$(snapshot_tree "${installed}")" == "${installed_before}" ]] || {
  echo "indexer-launcher-isolation-test: launcher mutated the installed payload" >&2
  exit 1
}
[[ "$(snapshot_tree "${idea_home}")" == "${idea_before}" ]] || {
  echo "indexer-launcher-isolation-test: launcher mutated the installed IDEA home" >&2
  exit 1
}

python3 - \
  "${capture_a}" \
  "${capture_a_restart}" \
  "${capture_b}" \
  "${idea_home}" \
  "${java_executable}" \
  "${private_plugins}" \
  "${cache_a}" \
  "${cache_b}" \
  "${socket_a}" \
  "${socket_b}" <<'PY'
from pathlib import Path
import sys

captures = [Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])]
idea_home = sys.argv[4]
java_executable = sys.argv[5]
private_plugins = sys.argv[6]
caches = [Path(sys.argv[7]), Path(sys.argv[7]), Path(sys.argv[8])]
sockets = [sys.argv[9], sys.argv[9], sys.argv[10]]
property_names = ("system", "config", "log")
observed = []

for capture, cache, socket in zip(captures, caches, sockets, strict=True):
    decoded = [item.decode() for item in capture.read_bytes().split(b"\0") if item]
    expected = {
        "system": str(cache / "system"),
        "config": str(cache / "config"),
        "log": str(cache / "log"),
    }
    current = {}
    for name in property_names:
        prefix = f"-Didea.{name}.path="
        matches = [argument for argument in decoded if argument.startswith(prefix)]
        if matches != [prefix + expected[name]]:
            print(
                f"indexer-launcher-isolation-test: idea.{name}.path is not exact: {matches}",
                file=sys.stderr,
            )
            raise SystemExit(1)
        current[name] = matches[0]
    required = (
        "-Xms256m",
        "-Xmx1536m",
        f"-Didea.plugins.path={private_plugins}",
        f"-Didea.home.path={idea_home}",
        f"--socket-path={socket}",
        f"--idea-home={idea_home}",
        f"--java-executable={java_executable}",
        f"-Dkast.cache.state.path={cache}/cache-state",
        "io.github.amichne.kast.indexer.KastIndexerMainKt",
    )
    for argument in required:
        if argument not in decoded:
            print(
                f"indexer-launcher-isolation-test: JVM argument missing: {argument}",
                file=sys.stderr,
            )
            raise SystemExit(1)
    classpath_index = decoded.index("-cp") + 1
    classpath = decoded[classpath_index].split(":")
    if not classpath[0].endswith("/runtime-libs/launcher.jar"):
        print("indexer-launcher-isolation-test: launcher jar is not first", file=sys.stderr)
        raise SystemExit(1)
    if classpath[-1] != f"{idea_home}/lib/*":
        print("indexer-launcher-isolation-test: installed IDEA libs absent", file=sys.stderr)
        raise SystemExit(1)
    observed.append(current)

if observed[0] != observed[1]:
    print(
        "indexer-launcher-isolation-test: restart did not reuse persistent cache paths",
        file=sys.stderr,
    )
    raise SystemExit(1)
if observed[0] == observed[2]:
    print(
        "indexer-launcher-isolation-test: distinct cache identities share IntelliJ paths",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY

launcher_arguments=(
  "--workspace-root=${workspace}"
  "--socket-path=${socket_a}"
  "--runtime-id=${runtime_id}"
  "--idea-home=${idea_home}"
  "--java-executable=${java_executable}"
  "--idea-system-path=${cache_a}/system"
  "--idea-config-path=${cache_a}/config"
  "--idea-log-path=${cache_a}/log"
  "--private-plugins-path=${private_plugins}"
  "--cache-state-path=${cache_a}/cache-state"
)
launcher_owned=(
  idea-home
  java-executable
  idea-system-path
  idea-config-path
  idea-log-path
  private-plugins-path
  cache-state-path
)
for omitted in "${launcher_owned[@]}"; do
  missing_capture="${fixture}/missing-${omitted}-capture"
  filtered=()
  for argument in "${launcher_arguments[@]}"; do
    [[ "${argument}" == "--${omitted}="* ]] || filtered+=("${argument}")
  done
  set +e
  missing_output="$(
    CAPTURE_FILE="${missing_capture}" \
      "${installed}/kast-indexer" "${filtered[@]}" 2>&1
  )"
  missing_status=$?
  set -e
  [[ ${missing_status} -ne 0 ]] || {
    echo "indexer-launcher-isolation-test: missing ${omitted} was accepted" >&2
    exit 1
  }
  [[ ! -e "${missing_capture}" ]] || {
    echo "indexer-launcher-isolation-test: Java started without ${omitted}" >&2
    exit 1
  }
  [[ "${missing_output}" == *"${omitted} argument is required"* ]] || {
    echo "indexer-launcher-isolation-test: missing ${omitted} diagnostic was not explicit" >&2
    exit 1
  }
done

echo "indexer-launcher-isolation-test: PASS"
