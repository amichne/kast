# Kast Agent Tool Surface

Use this file when the host can read Markdown instructions and run shell
commands, but does not load the full Kast skill, Copilot package, or LSP
adapter. Treat typed `kast agent` commands as the portable tool surface.

## Surface Check

Confirm the active binary exposes the current agent interface:

```sh
command -v kast
kast agent --help
kast agent verify --help
kast agent symbol --help
kast agent diagnostics --help
kast agent rename --help
```

If a required command is missing, report a stale Kast installation and upgrade
or reinstall the binary. Do not replace missing semantic tools with Kotlin text
search.

Keep using Kast after the first successful call for the same Kotlin or Gradle
task. Continue follow-up declaration inspection, references, callers,
diagnostics, impact checks, and edit planning through typed `kast agent`
commands until the task leaves Kotlin semantics or Kast reports a concrete
blocker.

## Readiness

Kast defaults to compact TOON outside interactive human terminals. Use JSON
output when a JSON-only result will drive later steps:

```sh
kast --output json ready --for agent --workspace-root "$PWD"
kast --output json ready --for kotlin --workspace-root "$PWD"
kast --output json agent verify --workspace-root "$PWD"
```

On macOS, workspace setup is valid only after the IntelliJ plugin has prepared
`.kast/setup/workspace.json`; do not run CLI resource setup as a fallback. On
non-macOS headless/server hosts, use `kast setup --dry-run --workspace-root
"$PWD"` when the host or repository should inspect the target before writing
files. Keep `--workspace-root "$PWD"` or an absolute workspace path on every
agent command so runtime and source-index state are tied to the intended
project.

When a non-macOS `setup` dry-run is used only to inspect setup, trust
`setup.targetDir` and copy `setup.installCommand` exactly; it includes the
selected executable and setup arguments.

## Typed Tools

Resolve identity before asking for references, callers, hierarchy, rename, or
edits:

```sh
kast agent symbol --query EventBean --workspace-root "$PWD"
kast agent symbol --query EventBean --references --workspace-root "$PWD"
kast agent symbol --query EventBean --callers incoming --workspace-root "$PWD"
```

Use diagnostics and impact commands for validation and change planning:

```sh
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
kast agent impact --symbol io.example.EventBean --workspace-root "$PWD"
```

Plan renames before applying them. Apply only after the plan is acceptable:

```sh
kast agent rename --symbol io.example.EventBean --new-name DomainEvent --workspace-root "$PWD"
kast agent rename --symbol io.example.EventBean --new-name DomainEvent --apply --workspace-root "$PWD"
```

Use direct inspect commands for source-index metrics when a task asks for graph
or ranking evidence:

```sh
kast developer inspect metrics fan-in --symbol io.example.EventBean --workspace-root "$PWD"
kast developer inspect metrics search EventBean --workspace-root "$PWD" --limit 10
```

The generic catalog transport and workflow helpers are not public setup assets
for this instruction bundle. Use typed commands first; if they cannot express a
required operation, report the bounded gap instead of inventing an offset,
catalog, or raw transport path.
