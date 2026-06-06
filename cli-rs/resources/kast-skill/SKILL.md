---
name: kast
description: >
  Semantic Kotlin code editing, navigation, diagnostics, source-index impact
  analysis, Gradle workflow routing, and validated refactoring through the Rust
  `kast` CLI. Use when an agent works on Kotlin or Gradle code and needs symbol
  identity, fuzzy symbol/file discovery, project reading, references, callers,
  hierarchy, diagnostics, safe edits, renames, or Kotlin build/test validation.
  Prefer Kast before text search for Kotlin; never use grep, rg, sed, or manual
  parsing to decide Kotlin identity, usage sets, hierarchy, or rename scope.
metadata:
  short-description: Semantic Kotlin navigation and validated edits
---

# Kast

Kast is the semantic operator surface for Kotlin work. The authoritative
executable is the Rust `kast` CLI. Assume no prior project knowledge: discover
workspace shape, symbols, and edit targets with Kast before opening or changing
Kotlin files.

## First Move

Run the narrowest useful Kast query before editing `.kt` or `.kts` files. The
normal setup is:

```bash
command -v kast
kast --help
```

If `kast` is missing, resolve `scripts/kast-session-start.sh` relative to this
skill directory and run it once for the current shell:

```bash
SKILL_DIR="/absolute/path/to/this/skill"
eval "$(bash "$SKILL_DIR/scripts/kast-session-start.sh")"
```

Then retry the same `kast ...` command. If the helper cannot resolve `kast`, or
reports a skill/CLI version mismatch, stop and report that setup blocker. Do not
fall back to non-semantic Kotlin search for symbol identity.

Capture JSON to files. Do not inspect large Kast responses in terminal output:

```bash
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
SKILL_DIR="/absolute/path/to/this/skill"
KAST_REQUEST="$KAST_TMP/request.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"
printf '%s\n' '{"jsonrpc":"2.0","method":"symbol/query","params":{"query":"Widget","modes":["exact","lexical"],"filters":{"relativePathPrefix":"src/"},"limit":10},"id":1}' >"$KAST_REQUEST"
python3 "$SKILL_DIR/scripts/validate-rpc-request.py" --request-file "$KAST_REQUEST" >"$KAST_TMP/validation.json"
kast rpc --request-file "$KAST_REQUEST" --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

Read `KAST_STDERR` only when the command fails.

## Editor Agent Loop

1. Orient semantically with tight bounds. Prefer `symbol/query` with
   `limit`, `filters`, and narrow modes; use `raw/workspace-symbol` for broad
   symbol lookup or `raw/workspace-search` for Kotlin literals/comments.
   Keep `raw/workspace-files` as a secondary module-summary tool and request
   file paths only with `moduleName` and a small `maxFilesPerModule`.
2. Identify the declaration. Use `symbol/discover` when names are ambiguous or
   when you have file, line, or snippet context. Feed returned `resolveParams`
   into `symbol/resolve`.
3. Inspect the smallest useful context. Use `symbol/scaffold` for an editable
   file view, `raw/file-outline` for a declaration tree, and
   `symbol/references`, `symbol/callers`, `raw/type-hierarchy`, or
   `raw/implementations` for relationships.
4. Edit with validation. Use `symbol/rename` for renames and
   `symbol/write-and-validate` for Kotlin create/insert/replace work. If the
   editor must apply the text patch itself, still use Kast for discovery before
   the edit and diagnostics after it.
5. Prove the result. Run `raw/workspace-refresh` after external edits when
   diagnostics look stale, then run `raw/diagnostics` or the focused Gradle task
   that proves the affected symbols.

## Known Request Shapes

Use `raw/workspace-symbol` with `pattern`, not `query`:

```json
{"jsonrpc":"2.0","method":"raw/workspace-symbol","params":{"pattern":"MyType","kind":"CLASS","maxResults":10},"id":1}
```

For offset-based raw methods, pass a zero-based byte offset:

```json
{"position":{"filePath":"/absolute/path/File.kt","offset":42}}
```

## Request Contract

The complete JSON-RPC contract is embedded with the skill in two forms:

- `references/commands.yaml` is the human-readable contract with categories,
  methods, request fields, nested object fields, variants, enum values,
  response types, and exposed `kast_*` tool metadata.
- `references/commands.json` is the machine contract used by validators and
  extension tooling.
- `references/requests/<category>/<method>/minimal.json` and `maximal.json`
  provide walkable request payload examples. Variant commands add one directory
  per `type` discriminator, such as
  `references/requests/symbol/rename/RENAME_BY_OFFSET_REQUEST/minimal.json`.

Validate every hand-authored or generated `kast rpc` request before sending it:

```bash
python3 "$SKILL_DIR/scripts/validate-rpc-request.py" --request-file "$KAST_REQUEST" >"$KAST_TMP/validation.json"
```

If validation returns `ok: false`, fix the payload and do not send it. Use the
`errors[*].path` entries as the source of truth for the broken field.

## Command Router

Load `references/commands.json` only when you need exact JSON-RPC field names,
required fields, response types, enum values, or request `type` variants.

| Need | Preferred route |
| --- | --- |
| Warm or inspect a workspace backend | `kast up`, `kast status`, `kast capabilities` |
| Discover indexed declarations with filters | `kast rpc` method `symbol/query` |
| Discover modules, then optionally bounded files | `kast rpc` method `raw/workspace-files` with `includeFiles=false` unless file paths are required |
| Fuzzy symbol discovery by name | `kast rpc` method `raw/workspace-symbol` |
| Guided symbol disambiguation | `kast rpc` method `symbol/discover` |
| Fuzzy text search in Kotlin source | `kast rpc` method `raw/workspace-search` |
| Read a Kotlin file semantically | `kast rpc` method `symbol/scaffold` |
| Read a lightweight declaration tree | `kast rpc` method `raw/file-outline` |
| Resolve exact declaration identity | `kast rpc` method `symbol/resolve` |
| Find usages | `kast rpc` method `symbol/references` |
| Trace callers or callees | `kast rpc` method `symbol/callers` |
| Offset-based resolve/references/hierarchy | `raw/resolve`, `raw/references`, `raw/call-hierarchy`, `raw/type-hierarchy` |
| Find implementations/subclasses | `raw/implementations` |
| Find insertion points, completions, actions | `raw/semantic-insertion-point`, `raw/completions`, `raw/code-actions` |
| Run diagnostics | `kast rpc` method `raw/diagnostics` |
| Rename safely | `kast rpc` method `symbol/rename` |
| Apply validated Kotlin edits or create files | `kast rpc` method `symbol/write-and-validate` |
| Apply raw edit plans or optimize imports | `raw/apply-edits`, `raw/optimize-imports` |
| Refresh after external edits | `raw/workspace-refresh` |
| Query source-index metrics through RPC | `kast rpc` method `database/metrics` |
| Query SQLite source-index directly | `kast metrics fan-in`, `fan-out`, `dead-code`, `impact`, `coupling`, `search`, `graph --json` |
| Explore indexed symbol graph interactively or as JSON | `kast demo`, usually with `--json` for agents |
| Run build/test validation | Use the repo Gradle command after Kast narrows the affected files or symbols |

## Request Rules

- Use camelCase for all JSON-RPC request and response fields.
- Pass `--workspace-root "$PWD"` unless you intentionally target another
  workspace root.
- Use absolute paths for `workspaceRoot`, `filePath`, `filePaths`,
  `targetFile`, and `contentFile` unless command help says otherwise.
- Check `ok` and `type` before projecting results. Treat `ok=false`,
  `*_FAILURE`, dirty diagnostics, validation failures, hash mismatches, and
  failed Gradle tasks as failed operations.
- Include the required `type` discriminator for `symbol/rename` and
  `symbol/write-and-validate` requests. Read `references/commands.json` for
  exact variant names.
- Use simple symbol names with `kind`, `containingType`, and `fileHint` to
  disambiguate. Do not pass fully-qualified names to `symbol/resolve` unless
  the catalog explicitly says a field wants one.
- Request larger context flags such as `includeDeclarationScope`,
  `includeDocumentation`, `surroundingLines`, or `includeSurroundingMembers`
  only when the task needs that context.
- If a JSON projection fails, inspect one sample object from `KAST_RESULT` and
  fix the projection.
- If output is too large, narrow the Kast query before post-processing.
- Treat direct SQLite metrics as index-backed guidance; use compiler-backed RPC
  methods for exact references, callers, hierarchy, diagnostics, and edits.

## Boundaries

Use `grep`, `rg`, `sed`, or normal file reads for non-Kotlin files, known paths,
docs, generated text, comments, string literals, and skill maintenance. Do not
use them to decide Kotlin symbol identity, usage sets, hierarchy, insertion
points, or rename scope.

When the user asks for a Kotlin edit and gives only a symbol name, first resolve
the symbol with Kast. When the user gives an exact Kotlin file and asks for a
local textual cleanup, read that file as needed, but run Kast diagnostics before
claiming the edit is done.

If `raw/workspace-symbol`, `symbol/discover`, `raw/workspace-search`, and
`kast metrics search` all find no candidate for the requested symbol, report
that the symbol is absent from the current checkout. At that point only, use
`rg` or `git grep` to verify absence or inspect history; label that as absence
verification, not semantic identity work.

## Source Of Truth

- Rust CLI command tree: `kast --help`, `kast <command> --help`, and
  `docs/reference/cli-cheat-sheet.md`.
- JSON-RPC method schemas and variants: `references/commands.yaml` for reading
  and `references/commands.json` for tooling.
- Minimal and maximal JSON-RPC request examples:
  `references/requests/<category>/<method>/`.
- Request preflight validator: `scripts/validate-rpc-request.py`.
- Examples for common workflows: `references/quickstart.md`.
- Bootstrap and binary resolution helpers: `scripts/kast-session-start.sh` and
  `scripts/resolve-kast.sh`.
- Skill maintenance fixtures: `fixtures/maintenance/`; do not load them during
  ordinary Kotlin work.
