#!/usr/bin/env bash
# Eval orchestrator — reads suite YAML definitions, feeds prompts to the
# agent, captures tool-call transcripts, runs assertions, and reports results.
set -euo pipefail

# ── Resolve repo root (parent of evals/) ────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ── Source shared logging helpers if available ───────────────────────────
source "$REPO_ROOT/scripts/lib.sh" 2>/dev/null || {
  # Minimal fallback logging when lib.sh is absent
  log()         { printf '│ %s\n' "$*" >&2; }
  log_section() { printf '\n%s\n' "$*" >&2; }
  log_step()    { printf '› %s\n' "$*" >&2; }
  log_success() { printf '✓ %s\n' "$*" >&2; }
  log_note()    { printf '• %s\n' "$*" >&2; }
  die()         { printf '✕ %s\n' "$*" >&2; exit 1; }
}

# ── Defaults ─────────────────────────────────────────────────────────────
SUITE=""
FORMAT="text"
WORKSPACE=""
TRANSCRIPT_DIR=""

# ── Argument parsing ────────────────────────────────────────────────────
usage() {
  cat >&2 <<EOF
Usage: $(basename "$0") --suite=<name> [OPTIONS]

Options:
  --suite=<name>            Suite name (reads evals/<name>.yaml)
  --format=json|text        Output format (default: text)
  --workspace=<path>        Override workspace from YAML
  --transcript-dir=<path>   Transcript directory (default: evals/transcripts/)
  -h, --help                Show this help message
EOF
  exit 1
}

for arg in "$@"; do
  case "$arg" in
    --suite=*)          SUITE="${arg#--suite=}" ;;
    --format=*)         FORMAT="${arg#--format=}" ;;
    --workspace=*)      WORKSPACE="${arg#--workspace=}" ;;
    --transcript-dir=*) TRANSCRIPT_DIR="${arg#--transcript-dir=}" ;;
    -h|--help)          usage ;;
    *) die "Unknown argument: $arg" ;;
  esac
done

[[ -n "$SUITE" ]] || { log_note "Missing required --suite argument"; usage; }
[[ "$FORMAT" == "json" || "$FORMAT" == "text" ]] || die "Invalid format: $FORMAT (expected json or text)"

# ── Locate suite YAML ───────────────────────────────────────────────────
SUITE_FILE="$REPO_ROOT/evals/$SUITE.yaml"
[[ -f "$SUITE_FILE" ]] || die "Suite file not found: $SUITE_FILE"

log_section "Eval suite: $SUITE"
log "Suite file: $SUITE_FILE"
log "Format:     $FORMAT"

# ── Temp directory for scratch files, cleaned up on exit ─────────────────
WORK_DIR="$REPO_ROOT/evals/.eval-run-$$"
mkdir -p "$WORK_DIR"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

# ── YAML → JSON helper (inline Python, stdlib only) ─────────────────────
# Emits a JSON object with keys: suite, workspace, cases (array).
# Handles the YAML subset used by our eval suites without requiring PyYAML.
parse_suite() {
  local yaml_file="$1"
  python3 - "$yaml_file" <<'PYEOF'
import json, sys, re

def parse_eval_yaml(path):
    """Minimal recursive-descent YAML parser for the eval-suite subset."""
    with open(path) as f:
        raw_lines = f.readlines()

    # Pre-process: strip inline comments, keep (indent, content), skip blanks
    tokens = []
    for raw in raw_lines:
        no_comment = re.sub(r'''(?<!["\'])\s*#.*$''', '', raw).rstrip()
        if not no_comment.strip():
            continue
        ind = len(no_comment) - len(no_comment.lstrip())
        tokens.append((ind, no_comment.strip()))

    pos = [0]

    def peek():
        return tokens[pos[0]] if pos[0] < len(tokens) else None

    def parse_scalar(text):
        text = text.strip()
        if not text:
            return ''
        if text in ('true', 'True', 'yes'):
            return True
        if text in ('false', 'False', 'no'):
            return False
        try:
            return int(text)
        except ValueError:
            pass
        if len(text) >= 2 and text[0] == text[-1] and text[0] in ('"', "'"):
            return text[1:-1]
        return text

    def parse_flow_sequence(text):
        text = text.strip()
        if text.startswith('[') and text.endswith(']'):
            inner = text[1:-1]
            return [parse_scalar(i.strip()) for i in inner.split(',') if i.strip()]
        return None

    def collect_block_scalar(fold_char, block_indent):
        lines = []
        while pos[0] < len(tokens):
            t = peek()
            if t is None or t[0] < block_indent:
                break
            lines.append(t[1])
            pos[0] += 1
        return (' ' if fold_char == '>' else '\n').join(lines)

    def parse_value(min_indent):
        t = peek()
        if t is None:
            return None
        _, text = t
        if text.startswith('- '):
            return parse_sequence(min_indent)
        if ':' in text and not text.startswith('{'):
            return parse_mapping(min_indent)
        pos[0] += 1
        return parse_scalar(text)

    def parse_inline_value(val, parent_indent):
        """Resolve a value that appeared after a colon on the same line."""
        if val in ('>', '|'):
            nt = peek()
            bi = nt[0] if nt else parent_indent + 2
            return collect_block_scalar(val, bi)
        flow = parse_flow_sequence(val)
        if flow is not None:
            return flow
        return parse_scalar(val)

    def parse_mapping(min_indent):
        result = {}
        while pos[0] < len(tokens):
            t = peek()
            if t is None:
                break
            ind, text = t
            if ind < min_indent or text.startswith('- ') or ':' not in text:
                break
            pos[0] += 1
            key, _, val = text.partition(':')
            key, val = key.strip(), val.strip()
            if val:
                result[key] = parse_inline_value(val, ind)
            else:
                nt = peek()
                if nt and nt[0] > ind:
                    result[key] = parse_value(nt[0])
                else:
                    result[key] = None
        return result

    def parse_sequence(min_indent):
        result = []
        while pos[0] < len(tokens):
            t = peek()
            if t is None:
                break
            ind, text = t
            if ind < min_indent or not text.startswith('- '):
                break
            pos[0] += 1
            item_content = text[2:].strip()
            item_body_indent = ind + 2

            if not item_content:
                nt = peek()
                if nt and nt[0] >= item_body_indent:
                    result.append(parse_value(item_body_indent))
                else:
                    result.append(None)
            elif ':' in item_content:
                # Sequence item starts a mapping
                key, _, val = item_content.partition(':')
                key, val = key.strip(), val.strip()
                obj = {}
                if val:
                    obj[key] = parse_inline_value(val, ind)
                else:
                    nt = peek()
                    if nt and nt[0] > ind:
                        obj[key] = parse_value(nt[0])
                    else:
                        obj[key] = None
                # Continue reading sibling mapping keys at item_body_indent
                while pos[0] < len(tokens):
                    t2 = peek()
                    if t2 is None or t2[0] < item_body_indent or t2[1].startswith('- '):
                        break
                    if ':' not in t2[1]:
                        break
                    pos[0] += 1
                    k2, _, v2 = t2[1].partition(':')
                    k2, v2 = k2.strip(), v2.strip()
                    if v2:
                        obj[k2] = parse_inline_value(v2, t2[0])
                    else:
                        nt = peek()
                        if nt and nt[0] > t2[0]:
                            obj[k2] = parse_value(nt[0])
                        else:
                            obj[k2] = None
                result.append(obj)
            else:
                result.append(parse_scalar(item_content))
        return result

    data = parse_value(0)
    if data is None:
        data = {}
    json.dump(data, sys.stdout, indent=2)
    sys.stdout.write('\n')

parse_eval_yaml(sys.argv[1])
PYEOF
}

# ── Parse suite ──────────────────────────────────────────────────────────
SUITE_JSON="$WORK_DIR/suite.json"
parse_suite "$SUITE_FILE" > "$SUITE_JSON" || die "Failed to parse suite YAML: $SUITE_FILE"

# Extract top-level workspace from YAML if not overridden by flag
if [[ -z "$WORKSPACE" ]]; then
  WORKSPACE="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('workspace',''))" "$SUITE_JSON")"
fi

# Make workspace absolute if it is a relative path
if [[ -n "$WORKSPACE" && "$WORKSPACE" != /* ]]; then
  WORKSPACE="$REPO_ROOT/$WORKSPACE"
fi

# Default transcript directory
if [[ -z "$TRANSCRIPT_DIR" ]]; then
  TRANSCRIPT_DIR="$REPO_ROOT/evals/transcripts"
fi

log "Workspace:      ${WORKSPACE:-<none>}"
log "Transcript dir: $TRANSCRIPT_DIR"

# ── Build kast if needed ─────────────────────────────────────────────────
KAST_BIN="$REPO_ROOT/kast/build/install/kast/bin/kast"
if [[ ! -x "$KAST_BIN" ]]; then
  log_step "Building kast (installDist)…"
  (cd "$REPO_ROOT" && ./gradlew :kast:installDist --no-daemon --quiet) || die "kast build failed"
  log_success "kast built"
fi

# ── Pre-warm daemon if workspace exists ──────────────────────────────────
if [[ -n "$WORKSPACE" && -d "$WORKSPACE" ]]; then
  log_step "Pre-warming daemon for workspace: $WORKSPACE"
  timeout 120 "$KAST_BIN" workspace ensure --workspace-root="$WORKSPACE" >&2 2>&1 || log_note "Daemon pre-warm returned non-zero (continuing)"
fi

# ── Extract cases and run assertions ─────────────────────────────────────
CASE_COUNT="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(len(d.get('cases',[])))" "$SUITE_JSON")"
log_section "Running $CASE_COUNT cases"

RESULTS_JSONL="$WORK_DIR/results.jsonl"
: > "$RESULTS_JSONL"

FAIL_COUNT=0

# Emit per-case JSON: {id, name, prompt, assertions} — one per line
python3 -c "
import json, sys
suite = json.load(open(sys.argv[1]))
for case in suite.get('cases', []):
    json.dump(case, sys.stdout)
    sys.stdout.write('\n')
" "$SUITE_JSON" > "$WORK_DIR/cases.jsonl"

CASE_IDX=0
while IFS= read -r case_json; do
  CASE_IDX=$((CASE_IDX + 1))

  CASE_ID="$(printf '%s' "$case_json"   | python3 -c "import json,sys; print(json.load(sys.stdin).get('id','unknown'))")"
  CASE_NAME="$(printf '%s' "$case_json"  | python3 -c "import json,sys; print(json.load(sys.stdin).get('name',''))")"

  log_step "[$CASE_IDX/$CASE_COUNT] $CASE_ID — $CASE_NAME"

  # ── Locate transcript ────────────────────────────────────────────────
  TRANSCRIPT_FILE="$TRANSCRIPT_DIR/$CASE_ID.jsonl"

  if [[ ! -f "$TRANSCRIPT_FILE" ]]; then
    log_note "  No transcript at $TRANSCRIPT_FILE — marking as skipped"
    printf '{"case_id":"%s","name":"%s","status":"skipped","reason":"no transcript"}\n' \
      "$CASE_ID" "$CASE_NAME" >> "$RESULTS_JSONL"
    continue
  fi

  # ── Write assertions to a temp file ──────────────────────────────────
  ASSERTIONS_FILE="$WORK_DIR/${CASE_ID}-assertions.json"
  printf '%s' "$case_json" | python3 -c "
import json, sys
case = json.load(sys.stdin)
json.dump(case.get('assertions', []), sys.stdout, indent=2)
sys.stdout.write('\n')
" > "$ASSERTIONS_FILE"

  # ── Run assertion checker ────────────────────────────────────────────
  ASSERT_OUTPUT="$WORK_DIR/${CASE_ID}-assert-out.json"
  if python3 "$HARNESS_DIR/assert-tool-usage.py" "$TRANSCRIPT_FILE" "$ASSERTIONS_FILE" > "$ASSERT_OUTPUT" 2>&1; then
    CASE_STATUS="pass"
    log_success "  PASS"
  else
    CASE_STATUS="fail"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    log_note "  FAIL"
    # Show assertion output on stderr for debugging
    cat "$ASSERT_OUTPUT" >&2 || true
  fi

  # ── Collect result ───────────────────────────────────────────────────
  python3 -c "
import json, sys
case_id   = sys.argv[1]
case_name = sys.argv[2]
status    = sys.argv[3]
detail_path = sys.argv[4]
detail = ''
try:
    with open(detail_path) as f:
        detail = f.read().strip()
except Exception:
    pass
json.dump({
    'case_id': case_id,
    'case_name': case_name,
    'suite': '$SUITE',
    'assertions': [{
        'assertion': case_name,
        'passed': status == 'pass',
        'expected': 'pass',
        'actual': status,
        'message': detail or status,
    }],
}, sys.stdout)
sys.stdout.write('\n')
" "$CASE_ID" "$CASE_NAME" "$CASE_STATUS" "$ASSERT_OUTPUT" >> "$RESULTS_JSONL"

done < "$WORK_DIR/cases.jsonl"

# ── Report ───────────────────────────────────────────────────────────────
log_section "Results"

python3 -c "
import json, sys
results = [json.loads(line) for line in sys.stdin if line.strip()]
json.dump(results, sys.stdout)
" < "$RESULTS_JSONL" | python3 "$HARNESS_DIR/report.py" --format="$FORMAT" 2>&1 || {
  # Fallback: emit raw JSONL if report.py is a placeholder / fails
  log_note "report.py failed; falling back to raw results"
  echo "---"
  if [[ "$FORMAT" == "json" ]]; then
    python3 -c "
import json, sys
results = [json.loads(line) for line in sys.stdin if line.strip()]
json.dump({'suite': sys.argv[1], 'results': results}, sys.stdout, indent=2)
sys.stdout.write('\n')
" "$SUITE" < "$RESULTS_JSONL"
  else
    PASS=0; FAIL=0; SKIP=0
    while IFS= read -r rline; do
      st="$(printf '%s' "$rline" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")"
      cid="$(printf '%s' "$rline" | python3 -c "import json,sys; print(json.load(sys.stdin).get('case_id',''))")"
      case "$st" in
        pass) PASS=$((PASS + 1)); printf '  ✓ %s\n' "$cid" ;;
        fail) FAIL=$((FAIL + 1)); printf '  ✕ %s\n' "$cid" ;;
        *)    SKIP=$((SKIP + 1)); printf '  ○ %s (skipped)\n' "$cid" ;;
      esac
    done < "$RESULTS_JSONL"
    printf '\nSuite: %s  |  Pass: %d  Fail: %d  Skip: %d\n' "$SUITE" "$PASS" "$FAIL" "$SKIP"
  fi
}

# ── Exit code ────────────────────────────────────────────────────────────
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  log_note "$FAIL_COUNT case(s) failed"
  exit 1
fi
log_success "All cases passed or skipped"
exit 0
