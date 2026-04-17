#!/usr/bin/env bash
# kast-demo.sh — Interactive comparison: grep vs kast semantic analysis.
# Usage: ./demo.sh [--workspace-root=/path] [--symbol=Name] [--format=markdown] [--kast=/path/to/kast]
#
# Picks a symbol from your workspace and shows what text search misses
# that kast's semantic analysis catches.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/scripts/lib.sh" ]; then
  # shellcheck source=scripts/lib.sh
  source "$SCRIPT_DIR/scripts/lib.sh"
else
  # Inline minimal helpers when lib.sh is unavailable (portable bundle)
  supports_color() {
    [[ "${CLICOLOR_FORCE:-}" == "1" ]] && return 0
    [[ -n "${NO_COLOR:-}" ]] && return 1
    [[ ! -t 2 ]] && return 1
    [[ "${TERM:-}" != "dumb" ]]
  }
  colorize() {
    local code="$1"; shift
    if supports_color; then printf '\033[%sm%s\033[0m' "$code" "$*"; else printf '%s' "$*"; fi
  }
  log_line()    { printf '%s %s\n' "$1" "$2" >&2; }
  log()         { log_line "$(colorize '2' '│')" "$*"; }
  log_step()    { log_line "$(colorize '1;34' '›')" "$*"; }
  log_success() { log_line "$(colorize '1;32' '✓')" "$*"; }
  log_note()    { log_line "$(colorize '33' '•')" "$*"; }
  die()         { log_line "$(colorize '1;31' '✕')" "$*"; exit 1; }
  can_prompt()  { [[ -r /dev/tty && -w /dev/tty ]]; }
fi

# ── Parse arguments ──────────────────────────────────────────────────────────
usage() {
  cat <<'USAGE' >&2
Usage: ./demo.sh [--workspace-root=/absolute/path] [--symbol=Name] [--format=markdown] [--kast=/path/to/kast]

Options:
  --workspace-root=...  Kotlin project root. Defaults to the current working directory.
  --symbol=...          Skip interactive picker; use the first matching symbol.
  --format=...          Output format: ansi (default) or markdown (no colors).
  --kast=...            Explicit kast binary path.
  --help, -h            Show this help.
USAGE
}

WORKSPACE_ROOT="$PWD"
KAST=""
SYMBOL_FILTER=""
FORMAT="ansi"
for arg in "$@"; do
  case "$arg" in
    --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
    --symbol=*)         SYMBOL_FILTER="${arg#*=}" ;;
    --format=*)         FORMAT="${arg#*=}" ;;
    --kast=*)           KAST="${arg#*=}" ;;
    --help|-h)          usage; exit 0 ;;
    *)                  die "Unknown argument: $arg" ;;
  esac
done

case "$FORMAT" in
  ansi|markdown) ;;
  *) die "Invalid --format: $FORMAT (expected ansi or markdown)" ;;
esac

if [ "$FORMAT" = "markdown" ]; then
  export NO_COLOR=1
fi

[ -d "$WORKSPACE_ROOT" ] || die "Workspace root does not exist: $WORKSPACE_ROOT"
WORKSPACE_ROOT="$(cd "$WORKSPACE_ROOT" && pwd)"

# ── Demo UI palette + layout ──────────────────────────────────────────────────
use_color_output() {
  [[ "$FORMAT" == "ansi" ]] && supports_color
}

if use_color_output; then
  C_RESET=$'\033[0m'
  C_BOLD=$'\033[1m'
  C_DIM=$'\033[2m'
  C_CYAN=$'\033[1;36m'
  C_GREEN=$'\033[1;32m'
  C_RED=$'\033[1;31m'
  C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[1;34m'
  C_MAGENTA=$'\033[35m'
  C_WHITE=$'\033[1;37m'
else
  C_RESET=''
  C_BOLD=''
  C_DIM=''
  C_CYAN=''
  C_GREEN=''
  C_RED=''
  C_YELLOW=''
  C_BLUE=''
  C_MAGENTA=''
  C_WHITE=''
fi

terminal_width() {
  if [[ "${COLUMNS:-}" =~ ^[0-9]+$ ]] && [ "${COLUMNS}" -ge 60 ]; then
    printf '%s\n' "$COLUMNS"
    return
  fi

  if can_prompt && command -v tput >/dev/null 2>&1; then
    local cols
    cols="$(tput cols 2>/dev/null || true)"
    if [[ "$cols" =~ ^[0-9]+$ ]] && [ "$cols" -ge 60 ]; then
      printf '%s\n' "$cols"
      return
    fi
  fi

  printf '%s\n' "100"
}

repeat_char() {
  local char="$1"
  local count="$2"
  [ "$count" -gt 0 ] || return 0
  printf '%*s' "$count" '' | tr ' ' "$char"
}

TERM_WIDTH="$(terminal_width)"
if [ "$TERM_WIDTH" -lt 78 ]; then
  UI_WIDTH=62
elif [ "$TERM_WIDTH" -gt 110 ]; then
  UI_WIDTH=88
else
  UI_WIDTH=$((TERM_WIDTH - 18))
fi

dim_text() {
  if use_color_output; then
    colorize '2' "$*"
  else
    printf '%s' "$*"
  fi
}

log_section_heading() {
  local title="$1"
  local fill_count=$((UI_WIDTH - ${#title} - 4))
  [ "$fill_count" -lt 0 ] && fill_count=0
  printf '\n%b── %s %b%s%b\n\n' \
    "$C_CYAN" \
    "$title" \
    "$C_DIM" \
    "$(repeat_char '─' "$fill_count")" \
    "$C_RESET" >&2
}

render_panel() {
  local title="$1"
  local body
  body="$(</dev/stdin)"
  PANEL_TITLE="$title" PANEL_BODY="$body" python3 - "$UI_WIDTH" <<'PY' >&2
import os
import re
import sys
import textwrap

title = os.environ["PANEL_TITLE"]
width = max(58, int(sys.argv[1]))
body = os.environ.get("PANEL_BODY", "").splitlines()
ansi = re.compile(r"\x1b\[[0-9;]*m")

use_color = not os.environ.get("NO_COLOR") and os.isatty(2)
C = "\033[1;36m" if use_color else ""
W = "\033[1;37m" if use_color else ""
X = "\033[0m" if use_color else ""
inner = max(40, width - 4)

def emit(text=""):
    visible = len(ansi.sub("", text))
    print(f"{C}│{X} {text}{' ' * max(0, inner - visible)} {C}│{X}")

def wrap_line(line):
    raw = line.rstrip()
    if not raw:
        return [""]
    stripped = raw.lstrip()
    indent = len(raw) - len(stripped)
    marker = ""
    marker_width = 0
    if stripped.startswith("- "):
        marker = "• "
        stripped = stripped[2:]
        marker_width = len(marker)
    elif stripped.startswith("> "):
        marker = "› "
        stripped = stripped[2:]
        marker_width = len(marker)
    initial = (" " * indent) + marker
    subsequent = " " * (indent + marker_width)
    wrap_width = max(12, inner - len(initial))
    if "/" in stripped and " " not in stripped and len(stripped) > wrap_width:
        tokens = [token for token in re.split(r"(/)", stripped) if token]
        lines = []
        current = initial
        for token in tokens:
            candidate = current + token
            if len(candidate) <= inner or current == initial:
                current = candidate
                continue
            lines.append(current)
            current = subsequent + token.lstrip()
        lines.append(current)
        return lines
    wrapped = textwrap.wrap(
        stripped,
        width=wrap_width,
        initial_indent=initial,
        subsequent_indent=subsequent,
        break_long_words=True,
        break_on_hyphens=False,
    )
    return wrapped or [initial.rstrip()]

print(f"{C}╭{'─' * (inner + 2)}╮{X}")
emit(f"{W}{title}{X}")
if body:
    emit()
    for line in body:
        for wrapped_line in wrap_line(line):
            emit(wrapped_line)
print(f"{C}╰{'─' * (inner + 2)}╯{X}")
PY
}

# ── Discover kast binary (same cascade as smoke.sh) ─────────────────────────
if [ -z "$KAST" ]; then
  if command -v kast >/dev/null 2>&1; then
    KAST="$(command -v kast)"
  elif [ -n "${KAST_CLI_PATH:-}" ] && [ -x "${KAST_CLI_PATH}" ]; then
    KAST="$KAST_CLI_PATH"
  else
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

# ── Temp dir + cleanup ───────────────────────────────────────────────────────
OUTDIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-demo.XXXXXX")"
trap '"$KAST" workspace stop --workspace-root="$WORKSPACE_ROOT" >/dev/null 2>&1 || true; rm -rf "$OUTDIR"' EXIT

# ── Timed command helper ──────────────────────────────────────────────────────
run_captured() {
  local label="$1"
  local stdout_file="$2"
  local stderr_file="$3"
  shift 3

  local start end elapsed rc
  start="$(python3 -c 'import time; print(time.monotonic())')"
  if "$@" >"$stdout_file" 2>"$stderr_file"; then
    rc=0
  else
    rc=$?
  fi
  end="$(python3 -c 'import time; print(time.monotonic())')"
  elapsed="$(python3 -c "start = float(${start}); end = float(${end}); print(f'{end - start:.2f}s')")"

  if [ "$rc" -eq 0 ]; then
    log_success "$label $(dim_text "(${elapsed})")"
  else
    log_line "$(colorize '1;31' '✕')" "$label $(dim_text "(${elapsed})")"
  fi
  return "$rc"
}

# ══════════════════════════════════════════════════════════════════════════════
#  Banner
# ══════════════════════════════════════════════════════════════════════════════
printf '\n' >&2
{
  printf 'semantic analysis vs text search\n\n'
  printf 'Workspace  %s\n' "$WORKSPACE_ROOT"
  printf 'Binary     %s\n' "$KAST"
} | render_panel "kast demo"
printf '\n' >&2

# ══════════════════════════════════════════════════════════════════════════════
#  Step 1 — Warm the daemon
# ══════════════════════════════════════════════════════════════════════════════
log_step "Warming workspace daemon..."
if ! run_captured \
    "workspace ensure" \
    "$OUTDIR/ensure.json" \
    "$OUTDIR/ensure.stderr" \
    "$KAST" workspace ensure \
      --workspace-root="$WORKSPACE_ROOT" \
      --wait-timeout-ms=180000; then
  cat "$OUTDIR/ensure.stderr" >&2 || true
  die "Failed to start daemon. Check that Java 21+ is available."
fi

# ══════════════════════════════════════════════════════════════════════════════
#  Step 2 — Enumerate symbols
# ══════════════════════════════════════════════════════════════════════════════
log_step "Discovering workspace symbols..."
SYMBOL_QUERY="."
SYMBOL_REGEX="true"
if [ -n "$SYMBOL_FILTER" ]; then
  SYMBOL_QUERY="$SYMBOL_FILTER"
  SYMBOL_REGEX="false"
fi

if ! "$KAST" workspace-symbol \
    --workspace-root="$WORKSPACE_ROOT" \
    --pattern="$SYMBOL_QUERY" \
    --regex="$SYMBOL_REGEX" \
    --max-results=500 \
    > "$OUTDIR/symbols.json" 2>"$OUTDIR/symbols.stderr"; then
  cat "$OUTDIR/symbols.stderr" >&2 || true
  die "workspace-symbol failed."
fi

# Format symbols for display
python3 - "$OUTDIR/symbols.json" "$WORKSPACE_ROOT" "$OUTDIR" <<'FORMAT_SYMBOLS'
import json, sys, os
from pathlib import Path

symbols_path = Path(sys.argv[1])
ws = sys.argv[2]
outdir = Path(sys.argv[3])

data = json.loads(symbols_path.read_text("utf-8"))
symbols = data.get("symbols", [])

lines = []
for s in symbols:
    loc = s["location"]
    rel = os.path.relpath(loc["filePath"], ws)
    # Format: KIND  fqName  relativePath  absolutePath:offset
    lines.append(
        f"{s['kind']:<12s} {s['fqName']:<55s} {rel}  {loc['filePath']}:{loc['startOffset']}"
    )

(outdir / "symbol_lines.txt").write_text(
    ("\n".join(lines) + "\n") if lines else "",
    encoding="utf-8",
)
FORMAT_SYMBOLS

symbol_count="$(wc -l < "$OUTDIR/symbol_lines.txt" | tr -d ' ')"
log_success "Found $symbol_count symbols"

if [ "$symbol_count" -eq 0 ]; then
  die "No symbols found in workspace. Is this a Kotlin project?"
fi

# Check truncation
python3 -c "
import json, sys
data = json.loads(open(sys.argv[1]).read())
page = data.get('page')
if page and page.get('truncated'):
    print('true')
else:
    print('false')
" "$OUTDIR/symbols.json" > "$OUTDIR/truncated.txt"
if [ "$(cat "$OUTDIR/truncated.txt")" = "true" ]; then
  log_note "Results truncated at $symbol_count symbols (large workspace)"
fi

# ══════════════════════════════════════════════════════════════════════════════
#  Step 3 — Symbol selection
# ══════════════════════════════════════════════════════════════════════════════
log_step "Selecting symbol..."

SELECTED_LINE=""

select_by_filter() {
  # Use workspace-symbol with the filter to find the best match
  if [ -z "$SYMBOL_FILTER" ]; then return 1; fi
  SELECTED_LINE="$(head -n 1 "$OUTDIR/symbol_lines.txt")"
  [ -n "$SELECTED_LINE" ]
}

select_by_fzf() {
  command -v fzf >/dev/null 2>&1 || return 1
  can_prompt || return 1
  SELECTED_LINE="$(
    fzf \
      --prompt="Pick a symbol › " \
      --header="Type to fuzzy-search your workspace symbols" \
      --height="~60%" \
      --layout=reverse \
      --border=rounded \
      --with-nth=1..3 \
      < "$OUTDIR/symbol_lines.txt"
  )" || return 1
  [ -n "$SELECTED_LINE" ]
}

select_by_filesystem_walk() {
  # Fallback: pick a random declaration from the workspace using the smoke.sh DECL_RE approach
  SELECTED_LINE="$(python3 - "$WORKSPACE_ROOT" <<'WALK_PICK'
import os, random, re, sys
from pathlib import Path

workspace = Path(sys.argv[1])

DECL_RE = re.compile(
    r'^[ \t]*(?:sealed\s+|enum\s+|data\s+|abstract\s+|open\s+|private\s+|internal\s+|public\s+|protected\s+)*'
    r'(?:class|object|interface|fun)\s+(?![A-Za-z_][A-Za-z0-9_]*\.)'
    r'([A-Za-z_][A-Za-z0-9_]*)',
    re.MULTILINE,
)
SKIP_DIRS = {'.git', '.gradle', '.kast', 'build', 'out', 'node_modules', '.idea', 'build-logic', 'buildSrc'}

candidates = []
for root, dirs, files in os.walk(str(workspace)):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith('.')]
    for fname in sorted(files):
        if not fname.endswith('.kt'):
            continue
        fpath = os.path.join(root, fname)
        try:
            text = open(fpath, encoding='utf-8').read()
        except Exception:
            continue
        for m in DECL_RE.finditer(text):
            candidates.append((fpath, m.group(1), m.start(1)))

if not candidates:
    sys.exit(1)

chosen = random.choice(candidates)
rel = os.path.relpath(chosen[0], str(workspace))
print(f"CLASS        {chosen[1]:<55s} {rel}  {chosen[0]}:{chosen[2]}")
WALK_PICK
  )" || return 1
  [ -n "$SELECTED_LINE" ]
}

# Priority: --symbol override → fzf interactive → filesystem walk → fail
if select_by_filter; then
  log_success "Selected via --symbol filter"
elif select_by_fzf; then
  log_success "Selected via fzf"
elif select_by_filesystem_walk; then
  log_note "No fzf available — picked a random declaration"
else
  die "No symbol selected. Install fzf for interactive mode, or pass --symbol=Name"
fi

# Parse the selected line
read -r SYMBOL_FILE SYMBOL_OFFSET SYMBOL_NAME SYMBOL_KIND SYMBOL_FQNAME SYMBOL_REL_PATH <<< "$(
  python3 -c "
import sys
line = sys.argv[1]
parts = line.split()
kind = parts[0]
fq_name = parts[1]
# Last part is absolutePath:offset
last = parts[-1]
colon_idx = last.rfind(':')
file_path = last[:colon_idx]
offset = last[colon_idx+1:]
# relative path is second-to-last
rel_path = parts[-2] if len(parts) >= 4 else parts[-1]
simple_name = fq_name.rsplit('.', 1)[-1]
print(f'{file_path} {offset} {simple_name} {kind} {fq_name} {rel_path}')
" "$SELECTED_LINE"
)"

# ══════════════════════════════════════════════════════════════════════════════
#  Step 4 — "The Task" box
# ══════════════════════════════════════════════════════════════════════════════
printf '\n' >&2
{
  printf 'Symbol  %s\n' "$SYMBOL_FQNAME"
  printf 'Kind    %s\n' "$SYMBOL_KIND"
  printf 'File\n'
  printf '  %s\n' "$SYMBOL_REL_PATH"
  printf 'Offset  %s\n' "$SYMBOL_OFFSET"
  printf '\n'
  printf '> Find semantic references without comment, import, or substring noise\n'
  printf '> Preview a safe rename before editing a single file\n'
  printf '> Trace incoming callers and compare that graph with grep output\n'
} | render_panel "demo target"
printf '\n' >&2

# ══════════════════════════════════════════════════════════════════════════════
#  Step 5 — Act 1: "Without Kast (grep)"
# ══════════════════════════════════════════════════════════════════════════════
log_section_heading "Act 1 · text search baseline"

grep -rn "$SYMBOL_NAME" "$WORKSPACE_ROOT" --include='*.kt' > "$OUTDIR/grep_raw.txt" 2>/dev/null || true

python3 - "$OUTDIR/grep_raw.txt" "$SYMBOL_NAME" "$WORKSPACE_ROOT" "$OUTDIR" <<'GREP_ANALYSIS' >&2
import json, os, re, sys
from pathlib import Path

grep_file = Path(sys.argv[1])
symbol = sys.argv[2]
ws = sys.argv[3]
outdir = Path(sys.argv[4])

no_color = os.environ.get("NO_COLOR", "")
use_color = not no_color and os.isatty(2)
G = "\033[1;32m" if use_color else ""   # green  — likely correct
R = "\033[1;31m" if use_color else ""   # red    — likely false positive
Y = "\033[33m"   if use_color else ""   # yellow — ambiguous
D = "\033[2m"    if use_color else ""   # dim
X = "\033[0m"    if use_color else ""   # reset

lines = grep_file.read_text("utf-8").strip().splitlines() if grep_file.stat().st_size > 0 else []

false_positives = 0
ambiguous = 0
correct = 0
total = len(lines)
categories = {"comment": 0, "string": 0, "import": 0, "substring": 0}
display_limit = 12

for i, line in enumerate(lines):
    # Parse grep output: filepath:linenum:content
    parts = line.split(":", 2)
    if len(parts) < 3:
        continue
    fpath, linenum, content = parts[0], parts[1], parts[2]
    rel = os.path.relpath(fpath, ws)
    content_stripped = content.strip()

    # Classify the match
    color = G
    label = ""

    # Check if in comment
    if content_stripped.startswith("//") or content_stripped.startswith("/*") or content_stripped.startswith("*"):
        color = R
        label = "comment"
        false_positives += 1
        categories["comment"] += 1
    # Check if in import
    elif content_stripped.startswith("import "):
        color = Y
        label = "import"
        ambiguous += 1
        categories["import"] += 1
    # Check if in string literal (simple heuristic: symbol appears inside quotes)
    elif f'"{symbol}' in content or f'{symbol}"' in content or f"'{symbol}" in content:
        color = R
        label = "string"
        false_positives += 1
        categories["string"] += 1
    # Check if substring collision (symbol is part of a longer identifier)
    elif re.search(r'[A-Za-z0-9_]' + re.escape(symbol), content) or \
         re.search(re.escape(symbol) + r'[a-z0-9_]', content):
        color = R
        label = "substring"
        false_positives += 1
        categories["substring"] += 1
    else:
        correct += 1

    if i < display_limit:
        tag = f" {D}← {label}{X}" if label else ""
        print(f"  {color}│{X} {D}{rel}:{linenum}{X}  {content_stripped[:80]}{tag}")

if total > display_limit:
    print(f"  {D}│ ... and {total - display_limit} more matches{X}")

print()
print(f"  grep found {G}{total}{X} matches for \"{symbol}\"")
if false_positives > 0:
    print(f"  {R}▸ {false_positives} likely false positives{X}  ", end="")
    parts = []
    for k, v in categories.items():
        if v > 0:
            parts.append(f"{v} {k}")
    print(f"({', '.join(parts)})")
if ambiguous > 0:
    print(f"  {Y}▸ {ambiguous} ambiguous{X}")
print(f"  {G}▸ {correct} likely correct{X}")

print()
# Count files that sed would touch
files_touched = set()
for line in lines:
    fpath = line.split(":")[0]
    files_touched.add(fpath)
print(f"  {D}sed -i \"s/{symbol}/{symbol}Renamed/g\" would touch {len(files_touched)} files{X}")
if false_positives > 0:
    print(f"  {R}▸ including {false_positives} locations that are NOT the symbol{X}")

# Save stats for the comparison table
stats = {
    "total": total,
    "false_positives": false_positives,
    "ambiguous": ambiguous,
    "correct": correct,
    "files_touched": len(files_touched),
}
(outdir / "grep_stats.json").write_text(json.dumps(stats), encoding="utf-8")
GREP_ANALYSIS

printf '\n' >&2

# ══════════════════════════════════════════════════════════════════════════════
#  Step 6 — Act 2: "With Kast (semantic)"
# ══════════════════════════════════════════════════════════════════════════════
log_section_heading "Act 2 · semantic analysis"

# ── resolve ──────────────────────────────────────────────────────────────────
log_step "resolve"
if run_captured \
    "resolve" \
    "$OUTDIR/resolve.json" \
    "$OUTDIR/resolve.stderr" \
    "$KAST" resolve \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$SYMBOL_FILE" \
      --offset="$SYMBOL_OFFSET" \
      --wait-timeout-ms=180000; then

  python3 - "$OUTDIR/resolve.json" "$WORKSPACE_ROOT" <<'SHOW_RESOLVE' >&2
import json, os, sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text("utf-8"))
ws = sys.argv[2]
s = data.get("symbol", {})
loc = s.get("location", {})
rel = os.path.relpath(loc.get("filePath", ""), ws) if loc.get("filePath") else "?"

no_color = os.environ.get("NO_COLOR", "")
use_color = not no_color and os.isatty(2)
D = "\033[2m" if use_color else ""
W = "\033[1;37m" if use_color else ""
X = "\033[0m" if use_color else ""

print(f"  {D}│{X} fqName:     {W}{s.get('fqName', '?')}{X}")
print(f"  {D}│{X} kind:       {s.get('kind', '?')}")
print(f"  {D}│{X} visibility: {s.get('visibility', '?')}")
print(f"  {D}│{X} location:   {rel}:{loc.get('startLine', '?')}")
if s.get('containingDeclaration'):
    print(f"  {D}│{X} container:  {s['containingDeclaration']}")
SHOW_RESOLVE
else
  cat "$OUTDIR/resolve.stderr" >&2 || true
fi
printf '\n' >&2

# ── references ───────────────────────────────────────────────────────────────
log_step "references"
if run_captured \
    "references" \
    "$OUTDIR/refs.json" \
    "$OUTDIR/refs.stderr" \
    "$KAST" references \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$SYMBOL_FILE" \
      --offset="$SYMBOL_OFFSET" \
      --include-declaration=true \
      --wait-timeout-ms=180000; then

  python3 - "$OUTDIR/refs.json" "$WORKSPACE_ROOT" <<'SHOW_REFS' >&2
import json, os, sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text("utf-8"))
ws = sys.argv[2]
refs = data.get("references", [])
scope = data.get("searchScope", {})

no_color = os.environ.get("NO_COLOR", "")
use_color = not no_color and os.isatty(2)
D = "\033[2m" if use_color else ""
G = "\033[1;32m" if use_color else ""
X = "\033[0m" if use_color else ""

print(f"  {D}│{X} references:  {G}{len(refs)}{X}")
print(f"  {D}│{X} exhaustive:  {scope.get('exhaustive', '?')}")
print(f"  {D}│{X} scope:       {scope.get('scope', '?')}")
print(f"  {D}│{X} searched:    {scope.get('searchedFileCount', '?')} / {scope.get('candidateFileCount', '?')} files")

display_limit = 8
for i, ref in enumerate(refs):
    if i >= display_limit:
        print(f"  {D}│ ... and {len(refs) - display_limit} more{X}")
        break
    rel = os.path.relpath(ref.get("filePath", ""), ws)
    preview = ref.get("preview", "").strip()[:70]
    print(f"  {D}│{X}   {D}{rel}:{ref.get('startLine', '?')}{X}  {preview}")
SHOW_REFS
else
  cat "$OUTDIR/refs.stderr" >&2 || true
fi
printf '\n' >&2

# ── rename (dry-run) ─────────────────────────────────────────────────────────
RENAME_NAME="${SYMBOL_NAME}Renamed"
log_step "rename --dry-run  (${SYMBOL_NAME} → ${RENAME_NAME})"
if run_captured \
    "rename (dry-run)" \
    "$OUTDIR/rename.json" \
    "$OUTDIR/rename.stderr" \
    "$KAST" rename \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$SYMBOL_FILE" \
      --offset="$SYMBOL_OFFSET" \
      --new-name="$RENAME_NAME" \
      --dry-run=true \
      --wait-timeout-ms=180000; then

  python3 - "$OUTDIR/rename.json" "$WORKSPACE_ROOT" <<'SHOW_RENAME' >&2
import json, os, sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text("utf-8"))
ws = sys.argv[2]
edits = data.get("edits", [])
affected = data.get("affectedFiles", [])
hashes = data.get("fileHashes", [])

no_color = os.environ.get("NO_COLOR", "")
use_color = not no_color and os.isatty(2)
D = "\033[2m" if use_color else ""
G = "\033[1;32m" if use_color else ""
X = "\033[0m" if use_color else ""

print(f"  {D}│{X} edits:          {G}{len(edits)}{X}")
print(f"  {D}│{X} affected files: {G}{len(affected)}{X}")
print(f"  {D}│{X} file hashes:    {len(hashes)} SHA-256 pre-images")

for f in affected[:6]:
    rel = os.path.relpath(f, ws)
    print(f"  {D}│{X}   {D}{rel}{X}")
if len(affected) > 6:
    print(f"  {D}│ ... and {len(affected) - 6} more{X}")
SHOW_RENAME
else
  cat "$OUTDIR/rename.stderr" >&2 || true
fi
printf '\n' >&2

# ── call-hierarchy ───────────────────────────────────────────────────────────
log_step "call-hierarchy (incoming, depth=2)"
if run_captured \
    "call-hierarchy" \
    "$OUTDIR/callhier.json" \
    "$OUTDIR/callhier.stderr" \
    "$KAST" call-hierarchy \
      --workspace-root="$WORKSPACE_ROOT" \
      --file-path="$SYMBOL_FILE" \
      --offset="$SYMBOL_OFFSET" \
      --direction=incoming \
      --depth=2 \
      --wait-timeout-ms=180000; then

  python3 - "$OUTDIR/callhier.json" "$WORKSPACE_ROOT" <<'SHOW_CALLS' >&2
import json, os, sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text("utf-8"))
ws = sys.argv[2]
stats = data.get("stats", {})

no_color = os.environ.get("NO_COLOR", "")
use_color = not no_color and os.isatty(2)
D = "\033[2m" if use_color else ""
G = "\033[1;32m" if use_color else ""
Y = "\033[33m" if use_color else ""
X = "\033[0m" if use_color else ""

print(f"  {D}│{X} total callers:  {G}{stats.get('totalNodes', 0)}{X}")
print(f"  {D}│{X} max depth:      {stats.get('maxDepthReached', '?')}")
print(f"  {D}│{X} files visited:  {stats.get('filesVisited', '?')}")
truncated = stats.get("timeoutReached", False) or stats.get("maxTotalCallsReached", False)
if truncated:
    print(f"  {Y}│ ⚠ results truncated{X}")

def print_tree(node, indent=0, limit=[12]):
    if limit[0] <= 0:
        return
    limit[0] -= 1
    sym = node.get("symbol", {})
    name = sym.get("fqName", "?").rsplit(".", 1)[-1]
    kind = sym.get("kind", "")
    prefix = "  " + "  " * indent + ("├─ " if indent > 0 else "")
    loc = sym.get("location", {})
    rel = os.path.relpath(loc.get("filePath", ""), ws) if loc.get("filePath") else ""
    line = loc.get("startLine", "")
    location_hint = f"{D}{rel}:{line}{X}" if rel else ""
    print(f"{prefix}{G}{name}{X} {D}({kind}){X}  {location_hint}")
    for child in node.get("children", []):
        print_tree(child, indent + 1, limit)

root = data.get("root")
if root:
    print_tree(root)
SHOW_CALLS
else
  cat "$OUTDIR/callhier.stderr" >&2 || true
fi
printf '\n' >&2

# ══════════════════════════════════════════════════════════════════════════════
#  Step 7 — "The Difference" comparison table
# ══════════════════════════════════════════════════════════════════════════════
log_section_heading "Side-by-side summary"

python3 - \
  "$OUTDIR/grep_stats.json" \
  "$OUTDIR/refs.json" \
  "$OUTDIR/rename.json" \
  "$OUTDIR/callhier.json" \
  "$UI_WIDTH" \
  <<'COMPARISON' >&2
import json, os, sys
from pathlib import Path
import textwrap

grep_stats = json.loads(Path(sys.argv[1]).read_text("utf-8"))

refs = {}
try: refs = json.loads(Path(sys.argv[2]).read_text("utf-8"))
except: pass

rename = {}
try: rename = json.loads(Path(sys.argv[3]).read_text("utf-8"))
except: pass

callhier = {}
try: callhier = json.loads(Path(sys.argv[4]).read_text("utf-8"))
except: pass

ui_width = max(66, int(sys.argv[5]))

no_color = os.environ.get("NO_COLOR", "")
use_color = not no_color and os.isatty(2)
C = "\033[1;36m" if use_color else ""
W = "\033[1;37m" if use_color else ""
X = "\033[0m" if use_color else ""

grep_total = grep_stats.get("total", 0)
grep_ambiguous = grep_stats.get("ambiguous", 0)
grep_correct = grep_stats.get("correct", 0)
files_touched = grep_stats.get("files_touched", 0)
ref_count = len(refs.get("references", []))
scope = refs.get("searchScope", {})
searched = scope.get("searchedFileCount", "?")
candidates = scope.get("candidateFileCount", "?")
exhaustive = "yes" if scope.get("exhaustive") is True else "no" if scope.get("exhaustive") is False else "?"
nodes = callhier.get("stats", {}).get("totalNodes", 0)
edits = len(rename.get("edits", []))
affected = len(rename.get("affectedFiles", []))
hashes = len(rename.get("fileHashes", []))

rows = [
    ("Matches found", f"{grep_total} total / {grep_correct} likely true / {grep_ambiguous} ambiguous", f"{ref_count} semantic references"),
    ("Symbol identity", "text only", "exact symbol identity"),
    ("Kind awareness", "none", "knows the declaration kind"),
    ("Call graph", "none", f"{nodes} incoming callers"),
    ("Rename plan", f"blind sed across {files_touched} files", f"{edits} edits across {affected} files"),
    ("Conflict detection", "none", f"{hashes} file hashes"),
    ("Coverage signal", "none", f"exhaustive={exhaustive} over {searched}/{candidates} files"),
    ("Post-edit checks", "manual", "kast diagnostics"),
]

inner = max(60, ui_width - 4)
c1 = 18
remaining = inner - c1 - 6
c2 = remaining // 2
c3 = remaining - c2

def wrap(text, width):
    return textwrap.wrap(text, width=width, break_long_words=False, break_on_hyphens=False) or [""]

def row_lines(row):
    left = wrap(row[0], c1)
    middle = wrap(row[1], c2)
    right = wrap(row[2], c3)
    height = max(len(left), len(middle), len(right))
    left += [""] * (height - len(left))
    middle += [""] * (height - len(middle))
    right += [""] * (height - len(right))
    return zip(left, middle, right)

print(f"  {C}┌{'─' * c1}┬{'─' * c2}┬{'─' * c3}┐{X}")
print(f"  {C}│{X}{'metric'.ljust(c1)}{C}│{X}{'grep + sed'.ljust(c2)}{C}│{X}{W}{'kast'.ljust(c3)}{X}{C}│{X}")
print(f"  {C}├{'─' * c1}┼{'─' * c2}┼{'─' * c3}┤{X}")
for row in rows:
    for left, middle, right in row_lines(row):
        print(f"  {C}│{X}{left.ljust(c1)}{C}│{X}{middle.ljust(c2)}{C}│{X}{right.ljust(c3)}{C}│{X}")
    if row != rows[-1]:
        print(f"  {C}├{'─' * c1}┼{'─' * c2}┼{'─' * c3}┤{X}")
print(f"  {C}└{'─' * c1}┴{'─' * c2}┴{'─' * c3}┘{X}")
COMPARISON

printf '\n' >&2

# ══════════════════════════════════════════════════════════════════════════════
#  Step 8 — Closing
# ══════════════════════════════════════════════════════════════════════════════
{
  printf 'grep only sees text, so it mixes real usages with imports, comments, string literals, and substring collisions.\n'
  printf 'kast resolves the exact declaration, returns true semantic references, previews a safe rename, and maps the incoming call graph before you edit anything.\n'
  printf '\n'
  printf 'Docs  https://amichne.github.io/kast/\n'
  printf 'Repo  https://github.com/amichne/kast\n'
} | render_panel "why the semantic pass wins"

printf '\n' >&2
log_success "Demo complete."
