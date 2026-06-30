# Kast quickstart

## Put `kast` on PATH

The skill is installed by the Kast binary, so the normal path is boring:
`command -v kast` succeeds and agents run `kast` directly.

```console
command -v kast
kast --help
kast agent --help
kast agent tools
kast agent workflow --help
```

Run the native package verification workflow before claiming the active binary,
install manifest, package files, or workspace are ready:

```console
kast --output json agent workflow package-verify --workspace-root "$PWD" --require-gradle-project
```

If the skill or Markdown instructions were installed into a host-specific root,
pass the same setup target root to package verification, for example
`--skill-target-dir "$PWD/.codex/skills"` or
`--instructions-target-dir "$PWD/.agents/instructions"`.

If `kast` is missing, `kast agent tools` fails, or `kast agent workflow --help`
is unavailable in an installed skill session, stop and report that the skill
and active binary are incompatible. Upgrade or reinstall Kast; do not switch to
non-semantic Kotlin search.

If `kast` exists but a command reports `NO_BACKEND_AVAILABLE`,
`INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a missing source-index
database, warm the IDEA backend before using non-semantic file tools:

```console
kast runtime up --workspace-root "$PWD" --backend idea
```

Kast opens IDEA or Android Studio dynamically only when
`runtime.ideaLaunch.enabled` allows it. If launch is not enabled, the command
reports that the project must be opened in the IDE with the Kast plugin
installed. That is the blocker; do not stop at the first missing-index result.

## Contract reference

The Rust `kast` command tree is the operator surface. Use `kast --help` and
`kast <command> --help` for direct CLI families such as `agent`, `runtime`,
`inspect`, `machine`, and `release`. `kast agent --help` is the public
agent-oriented entrypoint.

Use `kast agent up --dry-run --workspace-root "$PWD"` when you need to inspect
both the selected harness package and the runtime warmup command before writing
files or launching a backend. In JSON dry runs, read `setup.targetDir` and copy
`setup.installCommand` exactly when you want to install only the selected agent
resource; it includes the executable token and `--target-dir` chosen for that
workspace. Use `kast agent up --workspace-root "$PWD"` when the repository
should be prepared and warmed in one operator step.
In a smart human terminal, the first eligible non-JSON `kast agent up` may ask
whether to apply IDEA/Copilot onboarding globally or for the repository only.
Agents and scripts should use `kast --output json agent up ...` or pass
`--no-onboard` so prompts cannot block execution.

Use `kast agent setup auto --dry-run` when only package selection matters. It
derives its default target from the current directory unless `--target-dir` is
passed, and JSON output reports `targetDir` plus an executable `installCommand`.

For shell pipelines, use the public `kast agent` surface instead of hand-written
protocol plumbing. It emits one JSON envelope with `ok`, `method`, `request`,
and either `result` or `error`; `kast agent call <method>` accepts params,
full requests, previous envelopes, and `nextRequest` objects through stdin or
`--params-file`.

JSON request schemas, response types, discriminated variants, and field-level
notes are exposed through `kast agent tools`. Treat the discovered method
contract as the shape for requests sent through `kast agent call`, not as a
replacement for the Rust CLI help.

Use `kast agent tools` when an agent host can run CLI commands but cannot load
the full skill or Copilot package. It emits the catalog-backed tool names,
methods, descriptions, mutation metadata, default args, and params JSON Schemas
plus `result.invocation.argv`, so a generic host can call the same executable it
used for discovery with `<method>` replaced by the tool method.
Validate the discovery envelope before registering tools: `ok=true`,
`method=agent/tools`, `result.type=KAST_AGENT_TOOLS`, `schemaVersion >= 3`, a
SHA-256 `catalogSha256`, matching `toolCount`, and `result.invocation.argv`
shaped as `agent call <method>`. If validation fails, upgrade or reinstall the
active Kast binary instead of synthesizing tool specs from stale docs.

Use `kast agent tools` when you need exact field names, types, required vs
optional, enum values, variant discriminators, mutation metadata, default
arguments, or invocation argv. Keep raw transport debugging out of normal agent
workflows.

## Common patterns

Prefer native file-backed CLI calls for nontrivial methods:

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_PARAMS="$KAST_TMP/params.json"
KAST_RESULT="$KAST_TMP/stdout.json"
KAST_STDERR="$KAST_TMP/stderr.txt"
printf '%s\n' '{"query":"EventBean","modes":["exact","lexical"],"limit":10}' >"$KAST_PARAMS"
kast agent call symbol/query --params-file "$KAST_PARAMS" \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

For common multi-step evidence gathering, use the first-class workflow
commands. They preserve each step under one output directory:

```sh
kast agent workflow symbol --workspace-root "$PWD" \
  --dry-run --out-dir "$PWD/.kast-workflow" \
  --symbol EventBean --references
```

If `kast agent workflow --help` fails, stop and upgrade/reinstall the active
Kast CLI. The skill does not provide a maintained workflow runner for older
binaries.

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_PARAMS="$KAST_TMP/params.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

run_kast_agent() {
  method="$1"
  params="$2"
  printf '%s\n' "$params" >"$KAST_PARAMS"
  kast agent call "$method" --params-file "$KAST_PARAMS" \
    --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
}

# Query indexed declarations with tight bounds
run_kast_agent symbol/query '{"query":"EventBean","modes":["exact","lexical"],"filters":{"relativePathPrefix":"src/"},"limit":10}'

# Secondary module summary; request file paths only with moduleName and a small cap
run_kast_agent raw/workspace-files '{"moduleName":":analysis-api","includeFiles":false,"maxFilesPerModule":25}'

# Resolve an ambiguous symbol
kast agent resolve --symbol date --kind property \
  --containing-type com.example.EventBean --workspace-root "$PWD" >"$KAST_RESULT"

# Rank candidates before resolving
run_kast_agent symbol/discover '{"symbol":"date","fileHint":"/abs/path/EventBean.kt","line":42,"codeSnippet":"val date = event.date","maxResults":5}'

# Resolve with declaration context
kast agent resolve --symbol date --kind property \
  --containing-type com.example.EventBean --include-declaration-scope \
  --include-documentation --surrounding-lines 3 \
  --include-surrounding-members --workspace-root "$PWD" >"$KAST_RESULT"

# Find usages
kast agent references --symbol EventBean --include-declaration \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Trace callers
kast agent callers --symbol process --direction incoming --depth 3 \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Scaffold a file
kast agent scaffold --target-file /abs/path/EventBean.kt \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Rename
run_kast_agent symbol/rename '{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"}'

# Write and validate
run_kast_agent symbol/write-and-validate '{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."}'

# Diagnostics
kast agent raw-diagnostics --file-path /abs/path/File.kt \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Complex edit plans stay JSON-shaped
kast agent call raw/apply-edits --params-file "$KAST_PARAMS" \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Direct source-index metrics
kast inspect metrics impact com.example.EventBean --workspace-root "$PWD" --depth 3 \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Agent-readable symbol graph snapshot
kast inspect demo --workspace-root "$PWD" --view symbol --query EventBean --json \
  >"$KAST_RESULT" 2>"$KAST_STDERR"
```

## Recovery

- If a `jq` projection is wrong, inspect one item (e.g. `.references[0]`)
  before assuming field names.
- If a symbol name is broad, add `kind`, `containingType`, or `fileHint`.
- For large result sets, narrow the query before post-processing.
- If `kast agent` is unavailable, report a stale binary/skill installation.
- If install, config, active binary, or package state is unclear, run
  `kast agent workflow package-verify` and follow its recovery commands
  exactly; they preserve the selected executable token and include the stale
  skill or instruction target directory when one is known.
- Never pivot to `grep` or `rg` for Kotlin identity.
