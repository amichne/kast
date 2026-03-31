---
name: kast
description: Resolve and invoke Kast tooling deterministically for the current workspace. Use when Codex needs to inspect which Kast transports are currently available in this repository, choose between the repo-local CLI control plane and direct HTTP runtimes, or execute Kast operations with machine-readable JSON and explicit failure modes instead of ad hoc descriptor parsing or guessed entrypoints.
---

# Kast

Resolve the repository-local Kast entrypoints first, then run one of the transport wrappers instead of rebuilding descriptor logic in-line.

Prefer [`kast.py`](./scripts/kast.py) for normal work. Use the transport-specific wrappers only when the user explicitly requires one runtime path.

## Workflow

1. Resolve the current repo state.

   Run:

   ```bash
   ./.agents/skills/kast/scripts/kast-resolve.py resolve-tooling --workspace-root="$PWD"
   ```

   Read the JSON instead of inferring from file presence. The resolver reports:

   - the normalized repo root and workspace root
   - the descriptor directory in effect
   - whether the repo-local `analysis-cli` wrapper is present
   - whether the repo-local standalone wrapper is present
   - live IntelliJ and standalone runtime candidates, including readiness
   - the deterministic transport that `auto` would choose for the requested operation

2. Choose the transport with the narrowest acceptable freedom.

   Use `auto` when the task is "run Kast correctly for this workspace".
   Use `cli` when the task needs control-plane commands such as daemon start, daemon stop, or workspace ensure.
   Use `http-intellij` when the user explicitly wants the IntelliJ-backed runtime.
   Use `http-standalone` when the user explicitly wants the standalone runtime and it is already running.

3. Execute through a wrapper script.

   All wrappers emit JSON and fail with JSON. They do not print explanatory prose.

   ```bash
   ./.agents/skills/kast/scripts/kast.py \
     --workspace-root="$PWD" \
     --operation=workspace-status
   ```

   ```bash
   ./.agents/skills/kast/scripts/kast.py \
     --workspace-root="$PWD" \
     --operation=diagnostics \
     --request-file=/absolute/path/to/query.json
   ```

## Transport Rules

Apply these rules exactly.

- Treat `auto` as deterministic, not heuristic.
- Let explicit transport selection override everything else.
- Let `auto` prefer `cli` when the repo-local `analysis-cli` wrapper exists.
- Let `auto` resolve repo-local executables in this order: fat JAR first, wrapper script second.
- Let `auto` fall back to `http-intellij` before `http-standalone` only when `cli` is unavailable.
- Fail when the requested backend has zero ready candidates.
- Fail when the requested backend has more than one ready candidate.
- Never guess between two descriptors for the same backend.
- Require absolute `--request-file` paths for request-body operations.
- Prefer request files over inline JSON assembly in shell commands.

## Operations

Use these operation names.

- `workspace-status`
- `workspace-ensure`
- `daemon-start`
- `daemon-stop`
- `health`
- `runtime-status`
- `capabilities`
- `symbol-resolve`
- `references`
- `diagnostics`
- `rename`
- `edits-apply`

Use `workspace-status`, `workspace-ensure`, `daemon-start`, and `daemon-stop` through `cli` or `auto`.

Use `health`, `runtime-status`, `capabilities`, `symbol-resolve`, `references`, `diagnostics`, `rename`, and `edits-apply` through any transport, subject to runtime availability.

## Scripts

- [`kast-resolve.py`](./scripts/kast-resolve.py): Inspect repo-local tooling and live runtime candidates.
- [`kast.py`](./scripts/kast.py): Select a transport deterministically and execute one operation.
- [`kast-cli.py`](./scripts/kast-cli.py): Force the repo-local `analysis-cli` transport.
- [`kast-http-intellij.py`](./scripts/kast-http-intellij.py): Force the direct HTTP IntelliJ runtime path.
- [`kast-http-standalone.py`](./scripts/kast-http-standalone.py): Force the direct HTTP standalone runtime path.

## Request Files

Use absolute-path JSON request files for these operations.

- `symbol-resolve`
- `references`
- `diagnostics`
- `rename`
- `edits-apply`

Example `diagnostics` request:

```json
{
  "filePaths": [
    "/absolute/path/to/src/main/kotlin/example/Foo.kt"
  ]
}
```

Example `rename` request:

```json
{
  "position": {
    "filePath": "/absolute/path/to/src/main/kotlin/example/Foo.kt",
    "offset": 123
  },
  "newName": "renamedSymbol",
  "dryRun": true
}
```
