#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

fail() {
  echo "runtime compatibility contract: $*" >&2
  exit 1
}

required_paths=(
  "packaging/jetbrains/runtime-compatibility.json"
  ".github/scripts/render-runtime-compatibility.py"
  "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/compatibility/RuntimeCompatibilityFacts.kt"
  "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/compatibility/RuntimeCompatibilityMatrix.kt"
  "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/compatibility/RuntimeCompatibilityOutcome.kt"
  "analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/compatibility/RuntimeCompatibilityMatrixTest.kt"
  "cli-rs/tests/runtime_compatibility_metadata_smoke.rs"
)

for required_path in "${required_paths[@]}"; do
  [[ -f "$required_path" ]] || fail "missing required implementation owner: $required_path"
done

renderer=".github/scripts/render-runtime-compatibility.py"
source_file="packaging/jetbrains/runtime-compatibility.json"
release_tag="v0.13.0"
release_sha="0123456789abcdef0123456789abcdef01234567"
scratch_dir="$(mktemp -d)"
trap 'rm -rf "$scratch_dir"' EXIT

"$renderer" validate-source --source "$source_file"
python3 -m py_compile "$renderer"
"$renderer" render \
  --source "$source_file" \
  --release-tag "$release_tag" \
  --release-sha "$release_sha" \
  --output "$scratch_dir/manifest-a.json"
"$renderer" render \
  --source "$source_file" \
  --release-tag "$release_tag" \
  --release-sha "$release_sha" \
  --output "$scratch_dir/manifest-b.json"
cmp "$scratch_dir/manifest-a.json" "$scratch_dir/manifest-b.json"
"$renderer" validate-manifest \
  --manifest "$scratch_dir/manifest-a.json" \
  --release-tag "$release_tag"

python3 - "$scratch_dir/manifest-a.json" "$scratch_dir" <<'PY'
import copy
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
scratch = Path(sys.argv[2])

def write(name, mutate):
    value = copy.deepcopy(manifest)
    mutate(value)
    (scratch / f"invalid-manifest-{name}.json").write_text(
        json.dumps(value, indent=2) + "\n",
        encoding="utf-8",
    )

write("unknown-capability", lambda value: value["supportedPairs"][0]["requiredCapabilities"][0].__setitem__("name", "UNKNOWN"))
write("missing-capability", lambda value: value["supportedPairs"][0]["optionalCapabilities"].pop())
write("bad-build-range", lambda value: value["ideaBuildRange"].__setitem__("untilBuild", "252"))
write("unsafe-evidence", lambda value: value["supportedPairs"][0].__setitem__("evidence", ["../evidence"]))
PY

for invalid_manifest in "$scratch_dir"/invalid-manifest-*.json; do
  if "$renderer" validate-manifest \
    --manifest "$invalid_manifest" \
    --release-tag "$release_tag" \
    >"$invalid_manifest.out" 2>&1; then
    fail "invalid manifest unexpectedly passed validation: $(basename "$invalid_manifest")"
  fi
done

python3 - "$scratch_dir/manifest-a.json" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if manifest.get("releaseTag") != "v0.13.0":
    raise SystemExit("rendered compatibility manifest releaseTag drifted")
if manifest.get("releaseSha") != "0123456789abcdef0123456789abcdef01234567":
    raise SystemExit("rendered compatibility manifest releaseSha drifted")
pairs = manifest.get("supportedPairs")
if not isinstance(pairs, list) or not pairs:
    raise SystemExit("rendered compatibility manifest must contain supported pairs")
if any(pair.get("pluginVersion") != "0.13.0" for pair in pairs):
    raise SystemExit("same-release plugin template was not resolved")
if any(pair.get("cliVersion") != "0.13.0" for pair in pairs):
    raise SystemExit("same-release CLI template was not resolved")
PY

python3 - "$source_file" "$scratch_dir" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
scratch = Path(sys.argv[2])

def write(name, mutate):
    value = copy.deepcopy(source)
    mutate(value)
    (scratch / f"invalid-{name}.json").write_text(
        json.dumps(value, indent=2) + "\n",
        encoding="utf-8",
    )

write("zero-protocol", lambda value: value["supportedPairs"][0].__setitem__("protocolRevision", 0))
write("unknown-capability", lambda value: value["supportedPairs"][0]["requiredCapabilities"][0].__setitem__("name", "UNKNOWN"))
write("missing-capability", lambda value: value["supportedPairs"][0]["optionalCapabilities"].pop())
write("invalid-build-range", lambda value: value["ideaBuildRange"].__setitem__("untilBuild", "252"))
write("missing-same-release", lambda value: value.__setitem__("supportedPairs", []))
write("duplicate-pair", lambda value: value["supportedPairs"].append(copy.deepcopy(value["supportedPairs"][0])))
PY

python3 - "$source_file" "$scratch_dir/invalid-duplicate-key.json" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
Path(sys.argv[2]).write_text(
    source.replace('"schemaVersion": 1,', '"schemaVersion": 1,\n  "schemaVersion": 1,', 1),
    encoding="utf-8",
)
PY

for invalid_source in "$scratch_dir"/invalid-*.json; do
  if "$renderer" validate-source --source "$invalid_source" >"$invalid_source.out" 2>&1; then
    fail "invalid source unexpectedly passed validation: $(basename "$invalid_source")"
  fi
done

echo "runtime compatibility contract: ok"
