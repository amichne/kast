---
name: kast
description: >
  IDE-grade Kotlin/JVM semantic analysis and refactoring for agents. Use
  this skill whenever you'd otherwise reach for grep, sed, or hand-edits
  against Kotlin code. It exposes a live analysis daemon through a single
  binary — resolve symbols, find references, expand call hierarchies,
  rename safely across the workspace, scaffold new implementations, and
  apply edits with automatic diagnostics. Trigger on Kotlin requests such
  as "understand this Kotlin file", "trace this flow", "where is this
  used", "who calls this", "rename this symbol", "fix this Kotlin test",
  "workspace files", "workspace symbol", "semantic analysis", or any
  IDE-style operation on Kotlin. Prefer this over text search and manual
  edits for anything that touches Kotlin identity — text matches will lie
  about overloads, extensions, and supertypes; kast will not.
---

# Kast

IDE-grade Kotlin analysis and refactoring, exposed as a single executable
that speaks JSON in / JSON out. A companion hook or helper script should
guarantee `KAST_CLI_PATH` points at the binary before this skill runs, so
every command in this document invokes `"$KAST_CLI_PATH"` directly.

For portable installs, use `scripts/resolve-kast.sh` to resolve the Kast
binary and `scripts/kast-session-start.sh` to print an
`export KAST_CLI_PATH=...` fragment suitable for:

    eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"

## Shape of every call

    "$KAST_CLI_PATH" skill <command> <request>

`<request>` is **exactly one argument** and may be either:

1. an inline JSON object literal (single-quoted in the shell), or
2. an absolute path to a `.json` file containing the same object.

The response is a single JSON document on stdout. Anything on stderr is
lifecycle chatter from the daemon and is safe to ignore when `ok=true`.

Every successful response carries:

- `type` — discriminator tag (for example `RESOLVE_SUCCESS`,
  `REFERENCES_FAILURE`, `WRITE_AND_VALIDATE_SUCCESS`).
- `ok` — boolean. Treat `false` as a failed transaction: stop the
  current plan, read `log_file`, fix the cause, rerun.
- `query` — a normalized echo of the inputs. Useful when chaining
  commands so you can keep using the resolved `file_path` / `offset`.
- `log_file` — absolute path to the per-request daemon log.

## Casing is asymmetric — don't fight it

- **Request input** uses camelCase (`workspaceRoot`, `filePath`,
  `newName`, `includeDeclaration`, `maxTotalCalls`).
- **Response output** uses snake_case (`workspace_root`, `file_path`,
  `new_name`, `include_declaration`, `max_total_calls`).

Both shapes come straight from the Kotlin contract types. Don't
translate them; the daemon won't accept camelCase in responses or
snake_case in requests. The full schema lives in
`references/wrapper-openapi.yaml` — consult it before guessing.

## Workspace root

Every request targets one workspace. Resolution order:

1. `workspaceRoot` inside the request body
2. `KAST_WORKSPACE_ROOT` environment variable

No implicit "use git to find the root" fallback exists. If neither is
set the command fails with a `SKILL_VALIDATION` error. Pick one and
stick with it for the session.

## The command catalog

| Intent | Command |
| --- | --- |
| Locate a declaration by name | `skill resolve` |
| Find every usage of a symbol | `skill references` |
| Walk the call graph (incoming / outgoing) | `skill callers` |
| Check error/warning state for files | `skill diagnostics` |
| Rename a symbol across the workspace | `skill rename` |
| Gather implementation context for a target | `skill scaffold` |
| Create / insert / replace, then validate | `skill write-and-validate` |
| List modules and source files | `skill workspace-files` |

Two of these — `rename` and `write-and-validate` — are polymorphic and
require a `type` discriminator in the request body (see below).

## How to think about the workflow

Treat every non-trivial task as three phases:

1. **Navigate.** Turn the user's name for a thing into a concrete
   position with `resolve`, `workspace-files`, or `scaffold`. These are
   cheap and idempotent.
2. **Act.** Do the one mutation that matters — `rename`,
   `write-and-validate`, or a chain of reads with `references` /
   `callers` to decide what to write.
3. **Validate.** `diagnostics` on the files you touched, plus the
   diagnostics summary already embedded in mutation responses. Don't
   report success until both agree.

Skipping navigate → act produces wrong edits. Skipping act → validate
produces plausible-looking edits that break the build.

## Reference recipes

### Resolve a symbol

    "$KAST_CLI_PATH" skill resolve '{
      "symbol":"AnalysisServer",
      "fileHint":"analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt"
    }'

Narrow ambiguous matches with:

- `kind` — one of `class`, `interface`, `object`, `function`, `property`.
- `containingType` — fully qualified name of the enclosing declaration.
- `fileHint` — absolute or workspace-relative path. When a symbol exists
  in several modules, this is usually the fastest disambiguator.

### Find references

    "$KAST_CLI_PATH" skill references '{
      "symbol":"AnalysisServer",
      "includeDeclaration":true
    }'

`includeDeclaration` defaults to `true`; set it to `false` when you want
only call-sites, not the declaration itself.

### Walk the call hierarchy

    "$KAST_CLI_PATH" skill callers '{
      "symbol":"process",
      "direction":"incoming",
      "depth":3,
      "maxTotalCalls":256,
      "maxChildrenPerNode":64
    }'

- `direction` is `incoming` (who calls me) or `outgoing` (who I call).
- `depth` caps recursion. Default 2; increase deliberately — the graph
  grows fast.
- `maxTotalCalls` / `maxChildrenPerNode` bound the traversal so it
  stays responsive. Defaults are 256 and 64.

### Run diagnostics

    "$KAST_CLI_PATH" skill diagnostics '{
      "filePaths":[
        "/abs/path/File.kt",
        "/abs/path/Other.kt"
      ]
    }'

Paths must be absolute. The response's `clean` field is your quick
green-light; `errors` contains anything that would prevent a build.

### Rename a symbol — by name

    "$KAST_CLI_PATH" skill rename '{
      "type":"RENAME_BY_SYMBOL_REQUEST",
      "symbol":"OldName",
      "newName":"NewName"
    }'

### Rename a symbol — by explicit position

Use this when the name is ambiguous (e.g. an overload or a property
that shares a name with a class).

    "$KAST_CLI_PATH" skill rename '{
      "type":"RENAME_BY_OFFSET_REQUEST",
      "filePath":"/abs/path/File.kt",
      "offset":1234,
      "newName":"NewName"
    }'

The typical pattern is `resolve` → read `file_path` and `offset` from
the response → `rename` with `RENAME_BY_OFFSET_REQUEST`. This sidesteps
any naming collisions the workspace might contain.

Either variant returns an `ApplyEditsResult`, a list of
`affected_files`, and a `diagnostics` summary. `ok=true` means the
workspace compiles cleanly after the rename.

### Scaffold implementation context

Before writing a new implementation, pull everything you need in one
request:

    "$KAST_CLI_PATH" skill scaffold '{
      "targetFile":"/abs/path/Interface.kt",
      "targetSymbol":"MyInterface",
      "mode":"implement"
    }'

You get the file outline, the current file content, the resolved
target symbol, its references and type hierarchy, and a
`semantic_insertion` offset tailored to `mode`:

| mode | Where insertion points to |
| --- | --- |
| `implement` | end of the class body |
| `replace` | start of the class body |
| `consolidate` | bottom of the file |
| `extract` | after the imports |

### Write and validate — create a file

    "$KAST_CLI_PATH" skill write-and-validate '{
      "type":"CREATE_FILE_REQUEST",
      "filePath":"/abs/path/NewImpl.kt",
      "content":"package foo\n\nclass NewImpl"
    }'

### Write and validate — insert at an offset

    "$KAST_CLI_PATH" skill write-and-validate '{
      "type":"INSERT_AT_OFFSET_REQUEST",
      "filePath":"/abs/path/File.kt",
      "offset":42,
      "content":"override fun x() = y()\n"
    }'

### Write and validate — replace a range

    "$KAST_CLI_PATH" skill write-and-validate '{
      "type":"REPLACE_RANGE_REQUEST",
      "filePath":"/abs/path/File.kt",
      "startOffset":120,
      "endOffset":240,
      "content":"…"
    }'

For all three variants you may pass `contentFile` (absolute path) in
place of `content`. Prefer that when the payload is large, contains
newlines, or is built from another tool — it avoids shell-quoting
pitfalls and keeps the command line readable.

Every write-and-validate response has already run import optimization
and diagnostics for you; `ok` is true only when both came back clean.

### List workspace files

    "$KAST_CLI_PATH" skill workspace-files '{"includeFiles":true}'

Without `includeFiles` you get modules, source roots, and file counts —
the cheap version. Add `moduleName` to narrow to one module.

## Passing requests from a file

Any request form accepts a path to a `.json` file in place of the
inline literal. Use this whenever the payload is awkward to quote:

    cat > /tmp/new-impl.json <<'JSON'
    {
      "type":"CREATE_FILE_REQUEST",
      "filePath":"/abs/path/NewImpl.kt",
      "contentFile":"/tmp/new-impl.kt"
    }
    JSON
    "$KAST_CLI_PATH" skill write-and-validate /tmp/new-impl.json

## Working rules

- **Never grep for Kotlin identity.** `references` and `callers` are
  correct across overloads, extension receivers, type parameters, and
  supertype chains. Text search is not.
- **Always use absolute paths** for any field ending in `filePath` /
  `filePaths` / `contentFile`. Resolve relatives before sending.
- **When `ok=false`, read `log_file` before retrying.** The daemon's
  trace tells you whether the problem is your request shape, the
  workspace state, or something transient.
- **Don't swallow partial failures.** A rename that applies edits but
  leaves diagnostics dirty is not a successful rename.

## IDE-action lookup

| You want to… | Use |
| --- | --- |
| Go to declaration | `skill resolve` |
| Find usages | `skill references` |
| Call hierarchy — who calls this? | `skill callers` with `direction:"incoming"` |
| Call hierarchy — what does this call? | `skill callers` with `direction:"outgoing"` |
| Rename refactor | `skill rename` |
| Problems panel for a file | `skill diagnostics` |
| File outline / structure view | `skill scaffold` (see `outline`) |
| Find all implementations of an interface | `skill scaffold` with `mode:"implement"` (see `type_hierarchy`) |
| Project view / module map | `skill workspace-files` |
| New file with imports organized | `skill write-and-validate` + `CREATE_FILE_REQUEST` |
| Edit a region and re-check | `skill write-and-validate` + `REPLACE_RANGE_REQUEST` |

## Evaluation

Regression-check this skill against the CLI it targets:

    "$KAST_CLI_PATH" eval skill
    "$KAST_CLI_PATH" eval skill --format=markdown
    "$KAST_CLI_PATH" eval skill --compare=baseline.json

The evaluator scans this directory, checks structural, contract, and
completeness invariants, estimates the token budget, and emits a scored
result that's comparable across revisions.

## Routing improvement

Use this workflow when Kotlin-semantic requests are being handled with raw
search or when the skill is loading less often than expected.

1. Export real sessions as Markdown with `/share`.
2. Keep the raw exports and Copilot process logs immutable.
3. Run the routing corpus builder:

       python3 .agents/skills/kast/scripts/build-routing-corpus.py \
         --session-dir=/path/to/shared-exports \
         --logs-dir=/path/to/copilot/logs \
         --output-jsonl=build/skill-routing/routing-cases.jsonl \
         --output-markdown=build/skill-routing/routing-summary.md \
         --output-promotions=build/skill-routing/promotion-candidates.json

4. Review the sanitized promotion candidates and move durable misses into
   `evals/routing.json`.
5. Re-run `"$KAST_CLI_PATH" eval skill --compare=baseline.json`.

Read `references/routing-improvement.md` before changing trigger text,
`agents/openai.yaml`, or the routing eval corpus.
