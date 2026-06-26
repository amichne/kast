# Kast Agent Tool Surface

Use this file when the host can read Markdown instructions and run shell
commands, but does not load the full Kast skill, Copilot package, or LSP
adapter. Treat `kast agent` as the portable tool surface.

## Surface Check

Confirm the active binary exposes the current agent interface:

```sh
command -v kast
kast agent --help
kast agent tools
kast agent workflow --help
```

If any command is missing, report a stale Kast installation and upgrade or
reinstall the binary. Do not replace missing semantic tools with Kotlin text
search.

## Readiness Tools

Use JSON output when a result will drive later steps:

```sh
kast --output json agent up --workspace-root "$PWD" --dry-run
kast --output json agent setup auto --dry-run
kast agent ready --output json
kast --output json agent workflow verify --workspace-root "$PWD"
kast --output json agent workflow package-verify --workspace-root "$PWD"
kast --output json agent workflow package-verify --workspace-root "$PWD" \
  --require-copilot --copilot-target-dir "$PWD/.github" \
  --require-skill --skill-target-dir "$PWD/.codex/skills" \
  --require-instructions --instructions-target-dir "$PWD/.codex/instructions"
```

Use `--backend idea` or `--backend headless` when the runtime choice must be
explicit. Keep `--workspace-root "$PWD"` or an absolute workspace path on every
agent call so runtime and source-index state are tied to the intended project.
Use package verification `--require-*` flags only for resources the task needs.
When a Copilot, skill, or instruction package was installed with
`agent setup ... --target-dir`, pass that same setup target with
`--copilot-target-dir`, `--skill-target-dir`, or `--instructions-target-dir`;
the workflow fails if the required target is not manifest-backed and current.
Failed required resource checks include `requiredResources.issues[].recoveryArgv`
with the exact `kast agent setup ... --force` invocation to run.
In `--dry-run` mode, catalog-backed workflow steps report `nextRequest`;
`package-verify` reports `nextCommandArgv` because it is native CLI
verification, not a backend JSON-RPC method.
When an `agent up` dry-run is used only to inspect setup, trust
`setup.targetDir` and copy `setup.installCommand` exactly; it includes the
selected executable and `--target-dir`. When only package selection matters,
`agent setup auto --dry-run` derives the target from the current directory
unless `--target-dir` is passed, and reports `targetDir` with a matching
`installCommand`.

## Shallow Tools

Prefer the shallow aliases when their flags fit the task:

```sh
kast agent resolve --symbol EventBean --workspace-root "$PWD"
kast agent references --symbol EventBean --workspace-root "$PWD"
kast agent callers --symbol EventBean --workspace-root "$PWD"
kast agent workspace-symbol --pattern EventBean --workspace-root "$PWD" --max-results 10
kast agent workspace-search --pattern "EventBean" --workspace-root "$PWD" --max-results 20
kast agent workspace-files --workspace-root "$PWD"
kast agent raw-resolve --file-path "$PWD/src/main/kotlin/App.kt" --offset 128 --workspace-root "$PWD"
kast agent raw-references --file-path "$PWD/src/main/kotlin/App.kt" --offset 128 --workspace-root "$PWD"
kast agent raw-diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
kast agent metrics --metric fanIn --symbol io.example.EventBean --workspace-root "$PWD"
```

Resolve identity before asking for references, callers, hierarchy, rename, or
edits. Use raw commands only when you already have exact files and offsets.

## File-Backed Tools

Use `kast agent tools` when the host needs machine-readable tool names,
catalog methods, descriptions, mutation metadata, default args, and params JSON
Schemas. Then call `result.invocation.argv`, replacing `<method>` with the
returned tool `method`; this keeps alternate binary names and absolute binary
paths intact.
Validate the discovery envelope first: `ok=true`, `method=agent/tools`,
`result.type=KAST_AGENT_TOOLS`, `schemaVersion >= 3`, a SHA-256
`catalogSha256`, matching `toolCount`, and an invocation argv shaped as
`agent call <method>`. If that fails, report a stale binary or package install.

Use `kast agent call <method>` for nested payloads, generated request samples,
or catalog methods that do not have a shallow alias:

```sh
kast agent call symbol/query --params-file request.json --workspace-root "$PWD"
kast agent call raw/apply-edits --params-file request.json --workspace-root "$PWD"
kast agent call symbol/write-and-validate --params-file request.json --workspace-root "$PWD"
```

The params file may contain a params object, full JSON-RPC request, prior agent
envelope, or object with `nextRequest`. Use camelCase fields and absolute file
paths. A successful transport still fails the operation when the outer envelope
has `ok=false` or a nested result reports `ok=false`.

## Workflow Tools

Use workflows for multi-step proof or mutation paths:

```sh
kast agent workflow symbol --symbol EventBean --workspace-root "$PWD" --references
kast agent workflow impact --symbol io.example.EventBean --workspace-root "$PWD"
kast agent workflow diagnostics --workspace-root "$PWD" --file-path "$PWD/src/main/kotlin/App.kt"
kast agent workflow rename-plan --file-path "$PWD/src/main/kotlin/App.kt" --offset 128 --new-name NewName --workspace-root "$PWD"
kast agent workflow write-validate --file-path "$PWD/src/main/kotlin/App.kt" --offset 128 --content-file "$PWD/content.kt" --workspace-root "$PWD" --mode insert
```

Write workflows must choose the operation mode explicitly and must pass
`--allow-mutation` before writing. Prefer dry-run or planning workflows first,
then rerun with mutation enabled only after the plan and diagnostics are
acceptable.
