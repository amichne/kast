# Kast CLI Instructions

Use the Rust `kast` CLI before ordinary text search for Kotlin and Gradle
project navigation. Start by confirming the binary and command surface:

```sh
command -v kast
kast --help
```

For agent automation, prefer machine-readable output and explicit workspace
roots:

```sh
kast --output json status --workspace-root "$PWD"
kast --output json up --workspace-root "$PWD" --backend idea
```

Use human output only for operator-facing summaries. Use `--output json` when a
result will be parsed, stored, or used as evidence.

## Non-Interactive Rules

- Prefer `--output json` for agent-run operator commands.
- Pass command-specific mutation controls explicitly, such as `--apply`,
  `--dry-run`, or `--force`.
- Use `kast demo --json` for snapshots; the default demo opens an interactive
  TUI when stdout is a terminal.
- Use `kast install affected --apply` only when repairs should be applied.

## Common Commands

```sh
kast --output json status --workspace-root "$PWD"
kast --output json capabilities --workspace-root "$PWD"
kast metrics search EventBean --workspace-root "$PWD" --limit 10
kast demo --workspace-root "$PWD" --view symbol --query EventBean --json
```

If the backend is missing or indexing is stale, warm the IDEA backend before
falling back to non-semantic file tools:

```sh
kast up --workspace-root "$PWD" --backend idea
```
