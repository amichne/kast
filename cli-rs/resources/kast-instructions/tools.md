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

Keep using Kast after the first successful call for the same Kotlin or Gradle
task. Continue follow-up declaration inspection, references, callers,
diagnostics, and edit validation through `kast agent` or `kast agent workflow`
until the task leaves Kotlin semantics or Kast reports a concrete blocker.

## Readiness Tools

Use JSON output when a result will drive later steps:

```sh
kast --output json setup --workspace-root "$PWD" --dry-run
kast --output json setup --workspace-root "$PWD" --no-open-ide
kast --output json ready --for agent
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
Use `--no-open-ide` for `setup` in automation when a human terminal might be
attached; first-run interactive onboarding is an operator flow.
Use package verification `--require-*` flags only for resources the task needs.
When a Copilot, skill, or instruction package was installed to a custom target,
pass that same setup target with
`--copilot-target-dir`, `--skill-target-dir`, or `--instructions-target-dir`;
the workflow fails if the required target is not manifest-backed and current.
Failed required resource checks include `requiredResources.issues[].recoveryArgv`
with the exact recovery invocation to run.
In `--dry-run` mode, catalog-backed workflow steps report `nextRequest`;
`package-verify` reports `nextCommandArgv` because it is native CLI
verification, not a backend JSON-RPC method.
When a `setup` dry-run is used only to inspect setup, trust
`setup.targetDir` and copy `setup.installCommand` exactly; it includes the
selected executable and setup arguments.

## Shallow Tools

Prefer catalog calls when a workflow does not fit the task:

```sh
kast agent call symbol/resolve --params '{"symbol":"EventBean"}' --workspace-root "$PWD"
kast agent call symbol/references --params '{"symbol":"EventBean","includeDeclaration":true}' --workspace-root "$PWD"
kast agent call symbol/callers --params '{"symbol":"EventBean","direction":"INCOMING"}' --workspace-root "$PWD"
kast agent call raw/workspace-symbol --params '{"pattern":"EventBean","maxResults":10}' --workspace-root "$PWD"
kast agent call raw/workspace-search --params '{"pattern":"EventBean","maxResults":20}' --workspace-root "$PWD"
kast agent call raw/workspace-files --params '{}' --workspace-root "$PWD"
kast agent call raw/diagnostics --params '{"filePaths":["/abs/path/App.kt"]}' --workspace-root "$PWD"
kast agent call database/metrics --params '{"metric":"fanIn","symbol":"io.example.EventBean"}' --workspace-root "$PWD"
```

Resolve identity before asking for references, callers, hierarchy, rename, or
edits. Use raw catalog methods only when you already have exact files and offsets.

## File-Backed Tools

Use `kast agent tools` when the host needs compact machine-readable tool names,
catalog methods, mutation metadata, and invocation hints. Use
`kast --output json agent tools --full` when a host needs descriptions, default
args, and params JSON Schemas for registration. Then call
`result.invocation.argv`, replacing `<method>` with the returned tool `method`;
this keeps alternate binary names and absolute binary paths intact.
Validate the full discovery envelope first: `ok=true`, `method=agent/tools`,
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
