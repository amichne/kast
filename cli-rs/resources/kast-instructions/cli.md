# Kast CLI Instructions

Use the Rust `kast` CLI before ordinary text search for Kotlin and Gradle
project navigation. Use Kast for every `.kt` and `.kts` file and for Gradle
project facts that need semantic or install-state evidence. These instructions
are installed by the binary, so confirm the command surface and then use it:

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
kast --output json setup --workspace-root "$PWD" --dry-run
kast --output json setup --workspace-root "$PWD" --no-open-ide
kast --output json status --workspace-root "$PWD"
kast --output json developer runtime up --workspace-root "$PWD" --backend idea
```

Use human output only for operator-facing summaries. Use `--output json` when a
result will be parsed, stored, or used as evidence. `kast agent` defaults to
compact TOON; add `--full` to `agent call` when exact large response fields are
needed. For `setup` dry-runs, `setup.targetDir` is the resolved package target,
`setup.installCommand` is the install-only command for the selected guidance
package, and `runtimeCommand` shows the backend warmup that a real setup will
attempt.

## Non-Interactive Rules

- Prefer `--output json` for agent-run operator commands.
- Pass `--no-open-ide` to `kast setup` when a human TTY may be present but
  automation must not prompt.
- Pass command-specific mutation controls explicitly, such as `--dry-run` or
  `--force`.
- Keep follow-up Kotlin inspection, references, diagnostics, and edits on Kast
  after the first successful call.
- Use `kast developer inspect demo --json` for snapshots; the default demo opens an interactive
  TUI when stdout is a terminal.
- If `kast`, `kast agent tools`, or `kast agent workflow` is missing, report a
  stale instruction/binary install instead of falling back to Kotlin text
  search.
- Do not invoke Kast for unrelated docs/text work that has no Kotlin, Gradle,
  package-state, or source-index requirement.

## Common Commands

```sh
kast --output json status --workspace-root "$PWD"
kast --output json developer runtime capabilities --workspace-root "$PWD"
kast developer inspect metrics search EventBean --workspace-root "$PWD" --limit 10
kast developer inspect demo --workspace-root "$PWD" --view symbol --query EventBean --json
```

If the backend is missing or indexing is stale, warm the IDEA backend before
falling back to non-semantic file tools:

```sh
kast setup --workspace-root "$PWD" --backend idea --no-open-ide
```
