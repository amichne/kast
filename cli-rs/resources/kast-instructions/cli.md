# Kast CLI Instructions

Use the Rust `kast` CLI before ordinary text search for Kotlin and Gradle
project navigation. These instructions are installed by the binary, so confirm
the command surface and then use it:

```sh
command -v kast
kast --help
kast agent --help
kast agent tools
kast agent workflow --help
```

For agent automation, prefer machine-readable output and explicit workspace
roots:

```sh
kast --output json agent up --workspace-root "$PWD" --dry-run
kast --output json agent up --workspace-root "$PWD" --no-onboard
kast --output json agent setup auto --dry-run
kast --output json runtime status --workspace-root "$PWD"
kast --output json runtime up --workspace-root "$PWD" --backend idea
```

Use human output only for operator-facing summaries. Use `--output json` when a
result will be parsed, stored, or used as evidence. For `agent up` dry-runs,
`setup.targetDir` is the resolved package target and `setup.installCommand` is
the install-only command to copy exactly. For `agent setup auto --dry-run`,
`targetDir` and `installCommand` describe the selected package target without a
runtime warmup step.

## Non-Interactive Rules

- Prefer `--output json` for agent-run operator commands.
- Pass `--no-onboard` to `kast agent up` when a human TTY may be present but
  automation must not prompt.
- Pass command-specific mutation controls explicitly, such as `--dry-run` or
  `--force`.
- Use `kast inspect demo --json` for snapshots; the default demo opens an interactive
  TUI when stdout is a terminal.
- If `kast`, `kast agent tools`, or `kast agent workflow` is missing, report a
  stale instruction/binary install instead of falling back to Kotlin text
  search.

## Common Commands

```sh
kast --output json runtime status --workspace-root "$PWD"
kast --output json runtime capabilities --workspace-root "$PWD"
kast inspect metrics search EventBean --workspace-root "$PWD" --limit 10
kast inspect demo --workspace-root "$PWD" --view symbol --query EventBean --json
```

If the backend is missing or indexing is stale, warm the IDEA backend before
falling back to non-semantic file tools:

```sh
kast runtime up --workspace-root "$PWD" --backend idea
```
