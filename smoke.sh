#!/usr/bin/env bash
# kast-smoke-test.sh — Portable smoke test for all kast CLI flows against a real workspace.
# Usage: ./kast-smoke-test.sh [--workspace-root=/absolute/path] [--file=Name.kt] [--source-set=:module:main] [--symbol=Name] [--format=json] [--kast=/absolute/path/to/kast]
#
# If --workspace-root is omitted, defaults to the current working directory.
# If --kast is omitted, discovers kast via PATH, KAST_CLI_PATH, or resolve-kast.sh.
#
# Discovers every Gradle source set in the workspace, picks a random Kotlin
# declaration from each, and exercises every public kast CLI flow against it.
set -euo pipefail

# ── Helpers ──────────────────────────────────────────────────────────────────
supports_color() {
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then
    return 0
  fi
  if [[ -n "${NO_COLOR:-}" ]]; then
    return 1
  fi
  if [[ ! -t 2 ]]; then
    return 1
  fi
  [[ "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"
  shift

  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
    return
  fi

  printf '%s' "$*"
}

log_line() {
  local label="$1"
  local message="$2"
  printf '%s %s\n' "$label" "$message" >&2
}

log() {
  log_line "$(colorize '2' '│')" "$*"
}

log_step() {
  log_line "$(colorize '1;34' '›')" "$*"
}

log_note() {
  log_line "$(colorize '33' '•')" "$*"
}

pass() {
  log_line "$(colorize '1;32' '✓')" "$*"
}

fail() {
  log_line "$(colorize '1;31' '✕')" "$*"
  FAILURES=$((FAILURES + 1))
  if [[ -n "${OUTDIR:-}" ]]; then
    printf '%s\n' "$*" >> "$OUTDIR/failures.txt"
  fi
}

die() {
  log_line "$(colorize '1;31' '✕')" "$*"
  exit 1
}

usage() {
  cat <<'USAGE' >&2
Usage: ./smoke.sh [--workspace-root=/absolute/path/to/workspace] [--file=Name.kt] [--source-set=:module:main] [--symbol=Name] [--format=json] [--kast=/absolute/path/to/kast]

Options:
  --workspace-root=...  Workspace root to smoke-test. Defaults to the current working directory.
  --file=...          Only keep discovered declarations whose basename or relative path matches this text.
  --source-set=...    Only keep discovered declarations from matching :module:sourceSet keys.
  --symbol=...        Only keep discovered declarations whose symbol name matches this text.
  --format=...        Report format: json or markdown. Defaults to json.
  --kast=...          Explicit kast launcher or binary to exercise.
  --help, -h          Show this help.
USAGE
}

FAILURES=0
OUTDIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-smoke.XXXXXX")"
: > "$OUTDIR/failures.txt"
trap 'rm -rf "$OUTDIR"' EXIT

# ── Parse arguments ──────────────────────────────────────────────────────────
WORKSPACE_ROOT="$PWD"
KAST=""
FILE_FILTER=""
SOURCE_SET_FILTER=""
SYMBOL_FILTER=""
FORMAT="json"
for arg in "$@"; do
  case "$arg" in
    --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
    --file=*)           FILE_FILTER="${arg#*=}" ;;
    --source-set=*)     SOURCE_SET_FILTER="${arg#*=}" ;;
    --symbol=*)         SYMBOL_FILTER="${arg#*=}" ;;
    --format=*)         FORMAT="${arg#*=}" ;;
    --kast=*)           KAST="${arg#*=}" ;;
    --help|-h)          usage; exit 0 ;;
    *)                  die "Unknown argument: $arg" ;;
  esac
done

case "$FORMAT" in
  json|markdown) ;;
  *) die "Invalid value for --format: $FORMAT (expected json or markdown)" ;;
esac

[ -d "$WORKSPACE_ROOT" ] || die "Workspace root does not exist: $WORKSPACE_ROOT"
WORKSPACE_ROOT="$(cd "$WORKSPACE_ROOT" && pwd)"  # absolute

# Discover kast binary
if [ -z "$KAST" ]; then
  if command -v kast >/dev/null 2>&1; then
    KAST="$(command -v kast)"
  elif [ -n "${KAST_CLI_PATH:-}" ] && [ -x "${KAST_CLI_PATH}" ]; then
    KAST="$KAST_CLI_PATH"
  else
    # Try resolve-kast.sh from a skill directory
    skill_md="$(find "$WORKSPACE_ROOT" -name SKILL.md -path "*/kast/SKILL.md" -maxdepth 6 -print -quit 2>/dev/null || true)"
    if [ -n "$skill_md" ]; then
      skill_root="$(cd "$(dirname "$skill_md")" && pwd)"
      resolver="$skill_root/scripts/resolve-kast.sh"
      if [ -x "$resolver" ]; then
        KAST="$(bash "$resolver" 2>/dev/null || true)"
      fi
    fi
  fi
fi
[ -n "$KAST" ] && [ -x "$KAST" ] || die "kast binary not found. Pass --kast=/path/to/kast or add kast to PATH."
log_step "Kast smoke"
log "Using kast:  $KAST"
log "Workspace:   $WORKSPACE_ROOT"
log_note "Output:      $FORMAT"
if [[ -n "$FILE_FILTER" || -n "$SOURCE_SET_FILTER" || -n "$SYMBOL_FILTER" ]]; then
  log_note "Filters:     file=${FILE_FILTER:-<any>} source-set=${SOURCE_SET_FILTER:-<any>} symbol=${SYMBOL_FILTER:-<any>}"
else
  log_note "Filters:     one random declaration per discovered source set"
fi

# Daemon log path for diagnostics on failure
DAEMON_LOG="$WORKSPACE_ROOT/.kast/logs/standalone-daemon.log"
dump_daemon_log() {
  if [ -f "$DAEMON_LOG" ]; then
    log "--- Last 60 lines of daemon log ---"
    tail -n 60 "$DAEMON_LOG" >&2 || true
  fi
}
trap 'status=$?; [ $status -ne 0 ] && dump_daemon_log; "$KAST" daemon stop --workspace-root="$WORKSPACE_ROOT" >/dev/null 2>&1 || true; rm -rf "$OUTDIR"; exit $status' EXIT

# ── Shared JSON assertion helper (no jq dependency) ─────────────────────────
assert_json() {
  # Usage: assert_json <label> <json_file> <python_assertion_code>
  local label="$1" json_file="$2" assertion="$3"
  if python3 -c "
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text('utf-8'))
$assertion
" "$json_file" 2>"$OUTDIR/assert_err.txt"; then
    pass "$label"
  else
    fail "$label"
    cat "$OUTDIR/assert_err.txt" >&2 || true
  fi
}

# ══════════════════════════════════════════════════════════════════════════════
# 1. Version check
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 1: --version"
if "$KAST" --version > "$OUTDIR/version.txt" 2>&1; then
  pass "kast --version (exit 0)"
else
  fail "kast --version (exit $?)"
fi

# ══════════════════════════════════════════════════════════════════════════════
# 2. Workspace ensure
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 2: workspace ensure"
if "$KAST" workspace ensure \
    --workspace-root="$WORKSPACE_ROOT" \
    --wait-timeout-ms=180000 \
    > "$OUTDIR/ensure.json" 2> "$OUTDIR/ensure.stderr"; then
  pass "workspace ensure (exit 0)"
else
  fail "workspace ensure (exit $?)"
  cat "$OUTDIR/ensure.stderr" >&2 || true
  dump_daemon_log
  die "Cannot continue without a ready workspace"
fi

assert_json "ensure: state=READY" "$OUTDIR/ensure.json" "
s = data.get('selected', data)
state = s.get('runtimeStatus', s).get('state', s.get('state', ''))
assert state == 'READY', f'expected READY, got {state}'
"

# ══════════════════════════════════════════════════════════════════════════════
# 3. Workspace status
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 3: workspace status"
if "$KAST" workspace status \
    --workspace-root="$WORKSPACE_ROOT" \
    > "$OUTDIR/status.json" 2> "$OUTDIR/status.stderr"; then
  pass "workspace status (exit 0)"
else
  fail "workspace status (exit $?)"
fi

# ══════════════════════════════════════════════════════════════════════════════
# 4. Capabilities
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 4: capabilities"
if "$KAST" capabilities \
    --workspace-root="$WORKSPACE_ROOT" \
    --wait-timeout-ms=180000 \
    > "$OUTDIR/capabilities.json" 2> "$OUTDIR/capabilities.stderr"; then
  pass "capabilities (exit 0)"
else
  fail "capabilities (exit $?)"
fi

assert_json "capabilities: has RESOLVE_SYMBOL" "$OUTDIR/capabilities.json" "
assert 'RESOLVE_SYMBOL' in data['readCapabilities']
"
assert_json "capabilities: has FIND_REFERENCES" "$OUTDIR/capabilities.json" "
assert 'FIND_REFERENCES' in data['readCapabilities']
"
assert_json "capabilities: has CALL_HIERARCHY" "$OUTDIR/capabilities.json" "
assert 'CALL_HIERARCHY' in data['readCapabilities']
"
assert_json "capabilities: has DIAGNOSTICS" "$OUTDIR/capabilities.json" "
assert 'DIAGNOSTICS' in data['readCapabilities']
"
assert_json "capabilities: has RENAME" "$OUTDIR/capabilities.json" "
assert 'RENAME' in data['mutationCapabilities']
"

# ══════════════════════════════════════════════════════════════════════════════
# 5. Discover source sets and pick a random symbol from each
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 5: discover source sets + random symbols"
python3 - "$WORKSPACE_ROOT" "$OUTDIR" "$FILE_FILTER" "$SOURCE_SET_FILTER" "$SYMBOL_FILTER" <<'DISCOVER'
import json, os, random, re, sys
from pathlib import Path

workspace = Path(sys.argv[1])
outdir = Path(sys.argv[2])
file_filter = sys.argv[3].strip().lower()
source_set_filter = sys.argv[4].strip().lower()
symbol_filter = sys.argv[5].strip().lower()

# Conventional Gradle source-set roots: <module>/src/<sourceSet>/kotlin
# Also handles root-project layout:     src/<sourceSet>/kotlin
SRC_SET_RE = re.compile(r'/src/([a-zA-Z][a-zA-Z0-9]*)/(?:kotlin|java)(?:/|$)')

# Declaration patterns — top-level and nested
DECL_RE = re.compile(
    r'^[ \t]*(?:sealed\s+|enum\s+|data\s+|abstract\s+|open\s+|private\s+|internal\s+|public\s+|protected\s+)*'
    r'(?:class|object|interface|fun)\s+(?![A-Za-z_][A-Za-z0-9_]*\.)'
    r'([A-Za-z_][A-Za-z0-9_]*)',
    re.MULTILINE,
)

SKIP_DIRS = {'.git', '.gradle', '.kast', 'build', 'out', 'node_modules', '.idea', 'build-logic', 'buildSrc'}

# source_set_key -> list of (file_path, symbol_name, offset)
source_sets: dict[str, list[tuple[str, str, int]]] = {}

def file_matches(relative_path: str, file_path: str) -> bool:
    if not file_filter:
        return True
    relative_candidate = relative_path.replace(os.sep, '/').lower()
    file_name = Path(file_path).name.lower()
    return file_filter in relative_candidate or file_filter in file_name

def source_set_matches(source_set_key: str) -> bool:
    if not source_set_filter:
        return True
    return source_set_filter in source_set_key.lower()

def symbol_matches(symbol_name: str) -> bool:
    if not symbol_filter:
        return True
    return symbol_filter in symbol_name.lower()

for root, dirs, files in os.walk(str(workspace)):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith('.')]
    for fname in sorted(files):
        if not fname.endswith('.kt'):
            continue
        fpath = os.path.join(root, fname)
        rel = os.path.relpath(fpath, str(workspace))

        # Determine source set key from path
        m = SRC_SET_RE.search(fpath.replace(os.sep, '/'))
        if not m:
            continue
        source_set_id = m.group(1)  # e.g. "main", "test", "testFixtures"

        # Derive module name: everything before /src/ relative to workspace
        before_src = fpath[:fpath.index('/src/')].replace(os.sep, '/')
        module = os.path.relpath(before_src, str(workspace))
        if module == '.':
            module = ':'  # root project
        else:
            module = ':' + module.replace('/', ':')

        ss_key = f"{module}:{source_set_id}"
        if not source_set_matches(ss_key):
            continue

        try:
            text = open(fpath, encoding='utf-8').read()
        except Exception:
            continue

        for dm in DECL_RE.finditer(text):
            sym_name = dm.group(1)
            if not symbol_matches(sym_name) or not file_matches(rel, fpath):
                continue
            offset = dm.start(1)
            source_sets.setdefault(ss_key, []).append((fpath, sym_name, offset))

# Pick one random symbol per source set
selections = []
for ss_key in sorted(source_sets):
    candidates = source_sets[ss_key]
    chosen = random.choice(candidates)
    selections.append({
        'sourceSet': ss_key,
        'filePath': chosen[0],
        'symbol': chosen[1],
        'offset': chosen[2],
        'candidateCount': len(candidates),
    })

(outdir / 'source_set_symbols.json').write_text(
    json.dumps(selections, indent=2),
    encoding='utf-8',
)
DISCOVER

if [ ! -s "$OUTDIR/source_set_symbols.json" ]; then
  fail "No source sets or declarations matched the current filters"
  die "Cannot continue without at least one matching symbol"
fi

source_set_count="$(python3 -c "import json; print(len(json.loads(open('$OUTDIR/source_set_symbols.json').read())))")"
log "Found $source_set_count source set(s) with symbols"

# Print discovery summary
python3 - "$OUTDIR/source_set_symbols.json" <<'SUMMARY'
import json, sys
from pathlib import Path

selections = json.loads(Path(sys.argv[1]).read_text('utf-8'))
for s in selections:
    rel = s['filePath'].split('/src/')[-1] if '/src/' in s['filePath'] else s['filePath']
    print(f"  {s['sourceSet']:40s}  {s['symbol']:30s}  (1 of {s['candidateCount']:>4d})  src/{rel}", file=sys.stderr)
SUMMARY

# ══════════════════════════════════════════════════════════════════════════════
# 6–12. Run all flows for EACH source-set symbol
# ══════════════════════════════════════════════════════════════════════════════
python3 - "$OUTDIR/source_set_symbols.json" <<'EMIT_LOOP' > "$OUTDIR/loop_entries.txt"
import json, sys
from pathlib import Path
# Emit tab-separated lines: sourceSet \t filePath \t symbol \t offset
for s in json.loads(Path(sys.argv[1]).read_text('utf-8')):
    print(f"{s['sourceSet']}\t{s['filePath']}\t{s['symbol']}\t{s['offset']}")
EMIT_LOOP

ss_index=0
while IFS=$'\t' read -r ss_key filepath sym offset; do
  ss_index=$((ss_index + 1))
  ss_tag="[$ss_key]"
  ss_outdir="$OUTDIR/ss_${ss_index}"
  mkdir -p "$ss_outdir"

  log "────────────────────────────────────────"
  log_step "Source set $ss_index/$source_set_count: $ss_key"
  log "  Symbol: $sym  File: $filepath  Offset: $offset"

  # ── 6. Symbol resolve ──
  if "$KAST" symbol resolve \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$filepath" \
      --offset="$offset" \
      --wait-timeout-ms=180000 \
      > "$ss_outdir/resolve.json" 2> "$ss_outdir/resolve.stderr"; then
    pass "$ss_tag symbol resolve (exit 0)"
  else
    fail "$ss_tag symbol resolve (exit $?)"
    cat "$ss_outdir/resolve.stderr" >&2 || true
  fi
  assert_json "$ss_tag resolve: has fqName" "$ss_outdir/resolve.json" "
assert data.get('symbol', {}).get('fqName'), 'fqName is empty'
"
  assert_json "$ss_tag resolve: has kind" "$ss_outdir/resolve.json" "
kind = data.get('symbol', {}).get('kind', '')
valid = ('CLASS','INTERFACE','OBJECT','FUNCTION','PROPERTY','CONSTRUCTOR','ENUM_ENTRY','TYPE_ALIAS','PACKAGE','PARAMETER','LOCAL_VARIABLE','UNKNOWN')
assert kind in valid, f'unexpected kind: {kind}'
"
  assert_json "$ss_tag resolve: has location" "$ss_outdir/resolve.json" "
loc = data.get('symbol', {}).get('location', {})
assert loc.get('filePath'), 'location.filePath is empty'
assert isinstance(loc.get('startOffset'), int), 'location.startOffset missing'
"

  # ── 7. References ──
  if "$KAST" references \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$filepath" \
      --offset="$offset" \
      --include-declaration=true \
      --wait-timeout-ms=180000 \
      > "$ss_outdir/refs.json" 2> "$ss_outdir/refs.stderr"; then
    pass "$ss_tag references (exit 0)"
  else
    fail "$ss_tag references (exit $?)"
    cat "$ss_outdir/refs.stderr" >&2 || true
  fi
  assert_json "$ss_tag references: has array" "$ss_outdir/refs.json" "
assert isinstance(data.get('references'), list), 'references is not a list'
"
  assert_json "$ss_tag references: has declaration when requested" "$ss_outdir/refs.json" "
assert data.get('declaration') is not None, 'declaration is null despite --include-declaration=true'
"
  assert_json "$ss_tag references: page metadata present" "$ss_outdir/refs.json" "
page = data.get('page')
if page is not None:
    assert isinstance(page.get('truncated'), bool), 'page.truncated is not bool'
"

  # ── 8. Call hierarchy (incoming) ──
  if "$KAST" call hierarchy \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$filepath" \
      --offset="$offset" \
      --direction=incoming \
      --depth=2 \
      --wait-timeout-ms=180000 \
      > "$ss_outdir/call_in.json" 2> "$ss_outdir/call_in.stderr"; then
    pass "$ss_tag call hierarchy incoming (exit 0)"
  else
    fail "$ss_tag call hierarchy incoming (exit $?)"
    cat "$ss_outdir/call_in.stderr" >&2 || true
  fi
  assert_json "$ss_tag call hierarchy: has root" "$ss_outdir/call_in.json" "
assert data.get('root') is not None, 'root is null'
assert data['root'].get('symbol') is not None, 'root.symbol is null'
"
  assert_json "$ss_tag call hierarchy: has stats" "$ss_outdir/call_in.json" "
stats = data.get('stats', {})
assert isinstance(stats.get('totalNodes'), int), 'stats.totalNodes missing'
assert isinstance(stats.get('timeoutReached'), bool), 'stats.timeoutReached missing'
"

  # ── 9. Call hierarchy (outgoing) ──
  if "$KAST" call hierarchy \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$filepath" \
      --offset="$offset" \
      --direction=outgoing \
      --depth=1 \
      --wait-timeout-ms=180000 \
      > "$ss_outdir/call_out.json" 2> "$ss_outdir/call_out.stderr"; then
    pass "$ss_tag call hierarchy outgoing (exit 0)"
  else
    fail "$ss_tag call hierarchy outgoing (exit $?)"
    cat "$ss_outdir/call_out.stderr" >&2 || true
  fi

  # ── 10. Diagnostics ──
  if "$KAST" diagnostics \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-paths="$filepath" \
      --wait-timeout-ms=180000 \
      > "$ss_outdir/diags.json" 2> "$ss_outdir/diags.stderr"; then
    pass "$ss_tag diagnostics (exit 0)"
  else
    fail "$ss_tag diagnostics (exit $?)"
    cat "$ss_outdir/diags.stderr" >&2 || true
  fi
  assert_json "$ss_tag diagnostics: has array" "$ss_outdir/diags.json" "
assert isinstance(data.get('diagnostics'), list), 'diagnostics is not a list'
"
  assert_json "$ss_tag diagnostics: entries have required fields" "$ss_outdir/diags.json" "
for d in data.get('diagnostics', []):
    assert d.get('severity') in ('ERROR','WARNING','INFO'), f'bad severity: {d.get(\"severity\")}'
    assert d.get('message'), 'diagnostic missing message'
    assert d.get('location', {}).get('filePath'), 'diagnostic missing location.filePath'
"

  # ── 11. Rename (dry-run) ──
  if "$KAST" rename \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$filepath" \
      --offset="$offset" \
      --new-name="${sym}Renamed" \
      --dry-run=true \
      --wait-timeout-ms=180000 \
      > "$ss_outdir/rename.json" 2> "$ss_outdir/rename.stderr"; then
    pass "$ss_tag rename dry-run (exit 0)"
  else
    fail "$ss_tag rename dry-run (exit $?)"
    cat "$ss_outdir/rename.stderr" >&2 || true
  fi
  assert_json "$ss_tag rename: has edits" "$ss_outdir/rename.json" "
assert isinstance(data.get('edits'), list), 'edits is not a list'
assert len(data['edits']) > 0, 'edits array is empty'
"
  assert_json "$ss_tag rename: has fileHashes" "$ss_outdir/rename.json" "
assert isinstance(data.get('fileHashes'), list), 'fileHashes is not a list'
assert len(data['fileHashes']) > 0, 'fileHashes is empty'
"
  assert_json "$ss_tag rename: has affectedFiles" "$ss_outdir/rename.json" "
assert isinstance(data.get('affectedFiles'), list), 'affectedFiles is not a list'
"

  # ── 12. Workspace refresh (targeted) ──
  if "$KAST" workspace refresh \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-paths="$filepath" \
      > "$ss_outdir/refresh.json" 2> "$ss_outdir/refresh.stderr"; then
    pass "$ss_tag workspace refresh (exit 0)"
  else
    fail "$ss_tag workspace refresh (exit $?)"
    cat "$ss_outdir/refresh.stderr" >&2 || true
  fi
  assert_json "$ss_tag refresh: has refreshedFiles" "$ss_outdir/refresh.json" "
assert isinstance(data.get('refreshedFiles'), list), 'refreshedFiles missing'
"

done < "$OUTDIR/loop_entries.txt"

# ══════════════════════════════════════════════════════════════════════════════
# 13. Assemble smoke report
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 13: assemble smoke report"
python3 - "$OUTDIR" "$WORKSPACE_ROOT" "$KAST" "$FORMAT" "$FILE_FILTER" "$SOURCE_SET_FILTER" "$SYMBOL_FILTER" <<'PY'
import json, sys
from pathlib import Path

outdir = Path(sys.argv[1])
workspace_root = Path(sys.argv[2])
kast_path = sys.argv[3]
output_format = sys.argv[4]
file_filter = sys.argv[5] or None
source_set_filter = sys.argv[6] or None
symbol_filter = sys.argv[7] or None

def load(path):
    if not path.exists() or path.stat().st_size == 0:
        return None
    try:
        return json.loads(path.read_text('utf-8'))
    except Exception:
        return None

ensure     = load(outdir / 'ensure.json')
caps       = load(outdir / 'capabilities.json')
selections = load(outdir / 'source_set_symbols.json') or []
failed_checks_path = outdir / 'failures.txt'
failed_checks = [
    line.strip()
    for line in failed_checks_path.read_text('utf-8').splitlines()
    if line.strip()
] if failed_checks_path.exists() else []

selected_runtime = {}
runtime_status = {}
if ensure:
    selected_runtime = ensure.get('selected', ensure)
    runtime_status = selected_runtime.get('runtimeStatus', selected_runtime)
capability_source = caps or selected_runtime.get('capabilities', {}) or runtime_status

def relative_to_workspace(path_text):
    try:
        return str(Path(path_text).resolve().relative_to(workspace_root.resolve()))
    except Exception:
        return path_text

all_healthy = True
source_set_reports = []
for i, sel in enumerate(selections, 1):
    ss_dir = outdir / f"ss_{i}"
    resolve = load(ss_dir / 'resolve.json')
    refs    = load(ss_dir / 'refs.json')
    call_in = load(ss_dir / 'call_in.json')
    diags   = load(ss_dir / 'diags.json')
    rename  = load(ss_dir / 'rename.json')

    fq = resolve.get('symbol', {}).get('fqName', '?') if resolve else 'FAILED'
    kind = resolve.get('symbol', {}).get('kind', '?') if resolve else '?'
    ref_count = len(refs.get('references', [])) if refs else -1
    nodes = call_in.get('stats', {}).get('totalNodes', -1) if call_in else -1
    diag_count = len(diags.get('diagnostics', [])) if diags else -1
    edit_count = len(rename.get('edits', [])) if rename else -1

    ok = resolve is not None and resolve.get('symbol', {}).get('fqName')
    if not ok:
        all_healthy = False

    source_set_reports.append({
        'sourceSet': sel['sourceSet'],
        'symbol': sel['symbol'],
        'selectedFilePath': sel['filePath'],
        'selectedRelativePath': relative_to_workspace(sel['filePath']),
        'selectedOffset': sel['offset'],
        'candidateCount': sel['candidateCount'],
        'resolvedFqName': fq if fq != 'FAILED' else None,
        'kind': kind if resolve else None,
        'healthy': bool(ok),
        'metrics': {
            'referenceCount': ref_count,
            'callNodeCount': nodes,
            'diagnosticCount': diag_count,
            'renameEditCount': edit_count,
        },
    })

llm_ready = all([
    ensure is not None,
    caps is not None and 'RESOLVE_SYMBOL' in caps.get('readCapabilities', []),
    all_healthy,
    len(selections) > 0,
])
status = 'PASS' if not failed_checks and llm_ready else 'FAIL'
report = {
    'schemaVersion': 1,
    'workspaceRoot': str(workspace_root),
    'kastPath': kast_path,
    'format': output_format,
    'filters': {
        'file': file_filter,
        'sourceSet': source_set_filter,
        'symbol': symbol_filter,
    },
    'summary': {
        'status': status,
        'checksFailed': len(failed_checks),
        'sourceSetsTested': len(source_set_reports),
        'llmReady': llm_ready,
    },
    'runtime': {
        'state': runtime_status.get('state', 'UNKNOWN'),
        'backendName': capability_source.get('backendName', 'unknown'),
        'readCapabilities': capability_source.get('readCapabilities', []),
        'mutationCapabilities': capability_source.get('mutationCapabilities', []),
        'limits': capability_source.get('limits'),
    },
    'sourceSets': source_set_reports,
}
if failed_checks:
    report['failedChecks'] = failed_checks

def md(value):
    text = '' if value is None else str(value)
    return text.replace('|', '\\|')

def render_markdown():
    filters = []
    if file_filter:
        filters.append(f"`file={md(file_filter)}`")
    if source_set_filter:
        filters.append(f"`source-set={md(source_set_filter)}`")
    if symbol_filter:
        filters.append(f"`symbol={md(symbol_filter)}`")
    lines = [
        '# Kast smoke report',
        '',
        f'- **Status:** `{status}`',
        f'- **LLM ready:** `{str(llm_ready).lower()}`',
        f'- **Workspace root:** `{md(workspace_root)}`',
        f'- **Kast path:** `{md(kast_path)}`',
        f'- **Source sets tested:** `{len(source_set_reports)}`',
        f'- **Checks failed:** `{len(failed_checks)}`',
        f'- **Backend:** `{md(report["runtime"]["backendName"])}`',
        f'- **Daemon state:** `{md(report["runtime"]["state"])}`',
        f'- **Filters:** {", ".join(filters) if filters else "_none_"}',
        '',
        '## Source sets',
        '',
        '| Source set | Symbol | File | FQ name | Kind | Refs | Call nodes | Diags | Rename edits | Healthy |',
        '| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- |',
    ]
    for entry in source_set_reports:
        metrics = entry['metrics']
        lines.append(
            f"| `{md(entry['sourceSet'])}` | `{md(entry['symbol'])}` | `{md(entry['selectedRelativePath'])}` | "
            f"`{md(entry['resolvedFqName'])}` | `{md(entry['kind'])}` | {metrics['referenceCount']} | "
            f"{metrics['callNodeCount']} | {metrics['diagnosticCount']} | {metrics['renameEditCount']} | "
            f"`{str(entry['healthy']).lower()}` |"
        )
    if failed_checks:
        lines.extend(['', '## Failed checks', ''])
        lines.extend(f'- `{md(message)}`' for message in failed_checks)
    return '\n'.join(lines) + '\n'

report_path = outdir / 'report.out'
if output_format == 'markdown':
    report_path.write_text(render_markdown(), encoding='utf-8')
else:
    report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
PY

# ══════════════════════════════════════════════════════════════════════════════
# 14. Daemon stop
# ══════════════════════════════════════════════════════════════════════════════
log_step "Step 14: daemon stop"
if "$KAST" daemon stop \
    --workspace-root="$WORKSPACE_ROOT" \
    > "$OUTDIR/stop.json" 2> "$OUTDIR/stop.stderr"; then
  pass "daemon stop (exit 0)"
else
  # Non-fatal: daemon may have been managed externally
  log "daemon stop exited non-zero (may already be stopped)"
fi

# ══════════════════════════════════════════════════════════════════════════════
# Final summary
# ══════════════════════════════════════════════════════════════════════════════
cat "$OUTDIR/report.out"
if [ "$FAILURES" -eq 0 ]; then
  pass "All smoke checks passed"
  exit 0
else
  log_line "$(colorize '1;31' '✕')" "$FAILURES smoke check(s) failed"
  exit 1
fi
