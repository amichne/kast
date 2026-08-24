#!/usr/bin/env bash
set -euo pipefail

launcher="${1:?usage: test-kast-indexer-isolation.sh LAUNCHER}"
[[ -f "${launcher}" ]] || {
  echo "indexer-launcher-isolation-test: launcher is missing: ${launcher}" >&2
  exit 1
}

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-indexer-isolation.XXXXXX")"
cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT

installed="${fixture}/installed"
fake_java_home="${fixture}/fake-java"
mkdir -p \
  "${installed}/runtime-libs" \
  "${installed}/idea-home" \
  "${fake_java_home}/bin"
cp "${launcher}" "${installed}/kast-indexer"
chmod 755 "${installed}/kast-indexer"
printf '%s\n' 'launcher.jar' >"${installed}/runtime-libs/classpath.txt"
printf '%s\n' '{}' >"${installed}/idea-home/product-info.json"

cat >"${fake_java_home}/bin/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail

if [[ " $* " == *" -XshowSettings:properties "* ]]; then
  printf '    java.home = %s\n' "${FAKE_JAVA_HOME}" >&2
  exit 0
fi
printf '%s\0' "$@" >"${CAPTURE_FILE:?CAPTURE_FILE is required}"
FAKE_JAVA
chmod 755 "${fake_java_home}/bin/java"

socket_a="${fixture}/runtime/kast-aaaaaaaaaaaaaaaaaaaaaaaa.sock"
socket_b="${fixture}/runtime/kast-bbbbbbbbbbbbbbbbbbbbbbbb.sock"
runtime_id="sha256:$(printf 'c%.0s' {1..64})"

run_launcher() {
  local socket="$1"
  local capture="$2"
  FAKE_JAVA_HOME="${fake_java_home}" \
    JAVA_HOME="${fake_java_home}" \
    CAPTURE_FILE="${capture}" \
    "${installed}/kast-indexer" \
      --workspace-root="${fixture}/workspace" \
      --socket-path="${socket}" \
      --runtime-id="${runtime_id}"
}

capture_a="${fixture}/capture-a"
capture_a_restart="${fixture}/capture-a-restart"
capture_b="${fixture}/capture-b"
state_a="${socket_a}.state"
mkdir -p "${state_a}/idea/config"
printf '%s\n' 'stale-module-cache' >"${state_a}/idea/config/stale-module-cache"
printf '%s\n' 'durable-topology' >"${state_a}/topology.sqlite"
run_launcher "${socket_a}" "${capture_a}"
run_launcher "${socket_a}" "${capture_a_restart}"
run_launcher "${socket_b}" "${capture_b}"

[[ "$(<"${state_a}/topology.sqlite")" == "durable-topology" ]] || {
  echo "indexer-launcher-isolation-test: startup changed durable endpoint state" >&2
  exit 1
}

python3 - \
  "${capture_a}" \
  "${capture_a_restart}" \
  "${capture_b}" \
  "${socket_a}" \
  "${socket_b}" <<'PY'
from pathlib import Path
import sys

captures = [Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])]
sockets = [sys.argv[4], sys.argv[4], sys.argv[5]]
properties = ("config", "system", "plugins", "log")
observed = []

for capture, raw_socket in zip(captures, sockets, strict=True):
    socket = str(Path(raw_socket))
    arguments = capture.read_bytes().split(b"\0")
    decoded = [argument.decode() for argument in arguments if argument]
    current = {}
    for name in properties:
        prefix = f"-Didea.{name}.path="
        matches = [argument for argument in decoded if argument.startswith(prefix)]
        if len(matches) != 1:
            if name == "config":
                print(
                    "indexer-launcher-isolation-test: missing endpoint-scoped idea.config.path",
                    file=sys.stderr,
                )
            else:
                print(
                    f"indexer-launcher-isolation-test: missing endpoint-scoped idea.{name}.path",
                    file=sys.stderr,
                )
            raise SystemExit(1)
        current[name] = matches[0].removeprefix(prefix)
    roots = {str(Path(path).parent) for path in current.values()}
    if len(roots) != 1:
        print(
            "indexer-launcher-isolation-test: one process uses multiple IntelliJ roots",
            file=sys.stderr,
        )
        raise SystemExit(1)
    root = roots.pop()
    if not root.startswith(f"{socket}.state/idea."):
        print(
            "indexer-launcher-isolation-test: IntelliJ root is not process-scoped: "
            f"{root}, expected prefix {socket}.state/idea.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    if "-Didea.paths.selector=KastIndexer" in decoded:
        print(
            "indexer-launcher-isolation-test: shared KastIndexer selector remains",
            file=sys.stderr,
        )
        raise SystemExit(1)
    for required in (
        f"--socket-path={raw_socket}",
        "io.github.amichne.kast.indexer.KastIndexerMainKt",
    ):
        if required not in decoded:
            print(
                f"indexer-launcher-isolation-test: JVM argument missing: {required}",
                file=sys.stderr,
            )
            raise SystemExit(1)
    observed.append(current)

if observed[0] == observed[1]:
    print(
        "indexer-launcher-isolation-test: restart reused process-local IntelliJ paths",
        file=sys.stderr,
    )
    raise SystemExit(1)
if observed[0] == observed[2]:
    print(
        "indexer-launcher-isolation-test: distinct endpoints share IntelliJ paths",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY

missing_capture="${fixture}/missing-capture"
set +e
missing_output="$(
  FAKE_JAVA_HOME="${fake_java_home}" \
    JAVA_HOME="${fake_java_home}" \
    CAPTURE_FILE="${missing_capture}" \
    "${installed}/kast-indexer" \
      --workspace-root="${fixture}/workspace" \
      --runtime-id="${runtime_id}" 2>&1
)"
missing_status=$?
set -e
[[ ${missing_status} -ne 0 ]] || {
  echo "indexer-launcher-isolation-test: missing socket endpoint was accepted" >&2
  exit 1
}
[[ ! -e "${missing_capture}" ]] || {
  echo "indexer-launcher-isolation-test: Java started without a socket endpoint" >&2
  exit 1
}
[[ "${missing_output}" == *"socket-path argument is required"* ]] || {
  echo "indexer-launcher-isolation-test: missing socket diagnostic was not explicit" >&2
  exit 1
}

echo "indexer-launcher-isolation-test: PASS"
