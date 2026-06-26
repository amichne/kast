---
title: Agent Automation Commands
description: Use the public `kast agent` command surface for scripts and agents.
icon: lucide/bot
---

# Agent Automation Commands

`kast agent` is the advanced CLI surface for scripts, CI steps, and coding
agents. It is part of the public command tree because it is the preferred
machine-oriented command path.

## JSON envelope

Every `kast agent` command emits one JSON object on stdout. Treat `ok: false`
as a failed operation even when the process exited cleanly.

```json title="Envelope shape"
{
  "ok": true,
  "method": "raw/resolve",
  "request": { "method": "raw/resolve" },
  "result": {}
}
```

Stderr may contain human-readable startup or progress messages. Scripts should
parse stdout and keep stderr for diagnostics.

## Bring Up A Repository

Use `kast agent up` when a repository should be ready for an agent in one
step. It selects the agent harness, installs the selected resource package, and
warms the runtime for the resolved workspace root.

```console title="Plan and run agent bring-up"
kast agent up --dry-run
kast agent up --workspace-root "$PWD" --backend=headless
kast agent up --harness instructions --workspace-root "$PWD" --dry-run
```

When `--workspace-root` is supplied, setup targets that repository instead of
the shell's current directory. The command reports the selected setup command
and runtime command in both human and JSON output.
In JSON dry-runs, both `setup.installCommand` and `runtimeCommand` start with
the executable token used for the dry run, so copied binaries and absolute CLI
paths remain directly callable.

## Setup

Use `kast agent setup` to install repository-local agent resources. Copilot
repositories usually use the full LSP package, while enterprise or portable
harnesses can select skills or Markdown instructions without relying on a
Copilot extension or MCP availability.

```console title="Choose an agent harness"
kast agent setup copilot
kast agent setup auto --dry-run
kast agent setup auto --harness skill --target-dir "$PWD/.agents/skills" --force
kast agent setup auto --harness skill --target-dir "$PWD/.codex/skills" --force
kast agent setup auto --harness instructions --target-dir "$PWD/.agents/instructions" --force
kast agent setup auto --harness instructions --target-dir "$PWD/.codex/instructions" --force
```

When `--harness` is omitted, `kast agent setup auto` reads
`projectOpen.agentHarness` from config before falling back to repository
detection. Existing portable skill roots include `.agents/skills`,
`.codex/skills`, `.github/skills`, and `.claude/skills`; existing instruction
roots include `.agents/instructions`, `.codex/instructions`,
`.github/instructions`, and `.claude/instructions`. Those roots win before the
default Copilot package path.
In JSON dry-runs, `targetDir` reports the resolved package target and
`installCommand` includes that target plus the executable token used for the dry
run, so copied binaries and absolute CLI paths remain directly callable.

## Tool Discovery

Use `kast agent tools` when a CLI-capable host needs the same catalog-derived
tool surface that Copilot loads from the active CLI, without loading a Copilot
SDK, MCP adapter, or the full packaged skill. The command has no backend
dependency and returns tool names, catalog methods, descriptions, default args,
mutation metadata, and params JSON Schemas.

```console title="List catalog-backed tools"
kast agent tools
```

Invoke one of the returned specs through the returned
`result.invocation.argv`, replacing `<method>` with the spec's `method`, then
pass a params object or `--params-file`. The legacy `command` field remains a
readable `kast agent call` hint, while `argv` preserves the exact executable
token used to discover the tools. The `catalogSha256` field identifies the
embedded command catalog used to build the tool list.

Before registering or invoking returned tools, validate the discovery envelope:
`ok` is true, `method` is `agent/tools`, `result.type` is `KAST_AGENT_TOOLS`,
`schemaVersion` is at least 3, `catalogSha256` is a SHA-256 hex string,
`toolCount` matches the returned tools length, and `result.invocation.argv`
has the `agent call <method>` shape. Treat a failed validation as a stale
binary or package install.

## Alias commands

Use aliases for common shallow requests. They prepare the request object and
return the same envelope as `agent call`.

```console title="Resolve and trace from a file offset"
APP_FILE="$PWD/src/main/kotlin/App.kt"

kast agent raw-resolve --file-path "$APP_FILE" --offset 42
kast agent raw-references --file-path "$APP_FILE" --offset 42 --include-declaration
kast agent raw-call-hierarchy --file-path "$APP_FILE" --offset 42 --direction incoming --depth 3
```

Use name-based aliases when you know a Kotlin declaration name but not a file
offset.

```console title="Find and resolve by name"
kast agent workspace-symbol --pattern OrderService --max-results 20
kast agent resolve --symbol OrderService --kind class
kast agent references --symbol OrderService --kind class --include-declaration
```

## Structured calls

Use `kast agent call <method>` when a payload is too large for flags or when an
agent already has a structured request object.

```console title="Call a catalog method with a params file"
kast agent call raw/apply-edits --params-file /tmp/apply-edits.json
```

The input may be a params object, a full request, a prior agent envelope, or a
`nextRequest` object. Keep nested edit plans in files and pass them with
`--params-file`.

## File-backed workflows

`kast agent workflow` writes deterministic evidence files for multi-step
operations. Use `--dry-run` to create input and workflow files without calling
the backend.

```console title="Workflow evidence"
kast agent workflow verify --out-dir .kast-workflows/verify
kast --output json agent workflow package-verify \
  --workspace-root "$PWD" \
  --require-copilot --copilot-target-dir "$PWD/.github" \
  --require-skill --skill-target-dir "$PWD/.codex/skills" \
  --require-instructions --instructions-target-dir "$PWD/.codex/instructions"
kast agent workflow symbol --symbol OrderService --references --out-dir .kast-workflows/symbol
kast agent workflow rename-plan \
  --file-path "$PWD/src/main/kotlin/App.kt" \
  --offset 42 \
  --new-name processOrderSafely \
  --out-dir .kast-workflows/rename
```

Use `package-verify` when a script or agent must prove repository-local
resources are current before relying on them. `--require-copilot`,
`--require-skill`, and `--require-instructions` fail closed against the install
manifest. When a Copilot, skill, or instructions package was installed into a
nonstandard host root, pass the same setup target root with
`--copilot-target-dir`, `--skill-target-dir`, or `--instructions-target-dir`.
Failed required resource checks include `requiredResources.issues[].recoveryArgv`
with the exact `kast agent setup ... --force` invocation to run.
In `--dry-run` mode, catalog-backed workflow steps report `nextRequest`;
`package-verify` reports `nextCommandArgv` because it is native CLI verification,
not a backend JSON-RPC method.

Mutating workflow commands require explicit mutation opt-in. Do not treat a
dry-run workflow as proof that files changed.

## Raw RPC fallback

Use raw `kast rpc` only when debugging the low-level transport or reproducing a
protocol issue. Agent scripts should prefer `kast agent` because it normalizes
request inputs and returns a consistent envelope.
