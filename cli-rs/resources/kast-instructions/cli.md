# Kast CLI Instructions

Use the Rust `kast` CLI before ordinary text search for Kotlin and Gradle
project navigation. Use Kast for every `.kt` and `.kts` file and for Gradle
project facts that need semantic or install-state evidence. These instructions
are installed by the binary, so confirm the command surface and then use it:

```sh
command -v kast
kast --help
kast agent --help
kast agent verify --help
kast agent symbol --help
```

Kast defaults to compact TOON when stdout is not an interactive human terminal
or when it detects an agent process. For agent automation, keep explicit
workspace roots and use JSON only when a JSON consumer requires it:

```sh
kast --output json ready --workspace-root "$PWD"
kast --output json agent verify --workspace-root "$PWD"
kast --output json status --workspace-root "$PWD"
kast --output json developer runtime up --workspace-root "$PWD" --backend idea
```

Use human output only for operator-facing summaries. Use `--output json` when a
result will be parsed by a JSON-only consumer, stored, or used as evidence. Add
Set
`[cli] dynamicOutput = false` in `config.toml` to disable the interactive-human
fallback and keep implicit output TOON.

## Non-Interactive Rules

- Prefer implicit TOON for agent-run operator commands; pass `--output json`
  only for JSON consumers.
- On macOS, do not run `kast setup`; reopen the workspace in IntelliJ IDEA or
  Android Studio with the Kast plugin enabled.
- Use `kast developer machine plugin` for IDEA plugin install or repair.
- Pass command-specific mutation controls explicitly, such as `--dry-run` or
  `--force`.
- Keep follow-up Kotlin inspection, references, diagnostics, and edits on Kast
  after the first successful call.
- Use `kast developer inspect demo --json` for snapshots; the default demo opens an interactive
  TUI when stdout is a terminal.
- If `kast`, `kast agent verify`, or `kast agent symbol` is missing, report a
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
kast developer runtime up --workspace-root "$PWD" --backend idea
```
