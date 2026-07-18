# Kast command ownership

Use this file when a task needs install-state ownership, readiness boundaries, or
repeatable semantic command choices. It documents the v2 public dialect; generated
RPC catalogs and protocol samples remain internal contracts.

## Install and readiness

On macOS, the IntelliJ plugin prepares workspace guidance and metadata when the
project opens. `kast setup` fails closed there so agents cannot create partial
runtime/resource state.

On non-macOS headless/server installs, `kast setup` installs or repairs only
the repository agent assets:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected repo context file

Install the signed plugin with JetBrains **Install Plugin from Disk**, enroll
the published certificate and custom plugin repository in the IDE, and let
JetBrains own subsequent updates. `kast repair --for machine --apply` repairs
only the CLI receipt and recognized one-release legacy state.

Setup does not install Copilot packages, portable Markdown instruction
packages, session hooks, generated catalog copies, or workflow helper assets.

Use these health boundaries:

| Question | Command |
| --- | --- |
| workspace/runtime state | `kast status --workspace-root "$PWD"` |
| readiness for a task | `kast ready --for agent --workspace-root "$PWD"` |
| exact-root semantic lifecycle | `kast agent lease acquire|status|release` |
| semantic backend capability | `kast agent verify --workspace-root "$PWD" --backend <name> --lease-id <id>` |
| install-state repair plan | `kast repair --for agent --workspace-root "$PWD"` |
| install-state repair apply | `kast repair --for agent --workspace-root "$PWD" --apply` |

`ready` is read-only. `repair` is plan-only unless `--apply` is present.

## Semantic command patterns

Acquire once with `kast agent lease acquire --workspace-root "$PWD" --backend <idea|headless>`. Preserve its returned backend and opaque ID, append `--backend <name> --lease-id <id>` to every command below, and release that exact lease when the worker finishes.

Use compiler identity and typed selectors instead of offsets:

| Need | Command |
| --- | --- |
| symbol lookup | `kast agent symbol --query EventBean --workspace-root "$PWD"` |
| fuzzy symbol discovery | `kast agent symbol --query event --mode discovery --workspace-root "$PWD"` |
| references | `kast agent references --symbol com.example.EventBean --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 42 --kind class --workspace-root "$PWD"` |
| callers | `kast agent callers --symbol com.example.EventBean.process --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 96 --kind function --workspace-root "$PWD"` |
| diagnostics | `kast agent diagnostics --file-path src/main/kotlin/App.kt --workspace-root "$PWD"` |
| source-index impact | `kast agent impact --symbol com.example.EventBean --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 42 --kind class --workspace-root "$PWD"` |
| rename plan | `kast agent rename --symbol com.example.EventBean --new-name DomainEvent --workspace-root "$PWD"` |
| rename apply | `kast agent rename --symbol com.example.EventBean --new-name DomainEvent --apply --workspace-root "$PWD"` |

Use `agent symbol --query <name>` for lookup. Reserve
`--symbol <fq-name>` for compiler identity on commands that require a resolved
target.

Exact lookup returns `RESOLVED`, `NOT_FOUND`, or `AMBIGUOUS` without fuzzy
selection. Its source is `compiler` when the backend proves identity or
`indexed-exact` only when the compiler is unavailable and the source index can
prove every requested constraint. Fuzzy candidates are source `fuzzy` and
require explicit `--mode discovery`; choose a candidate and rerun exact lookup
before requesting references or callers.

## Removed surfaces

The following commands are intentionally not public v2 assets: `kast agent up`,
`kast agent ready`, `kast agent setup`, `kast agent tools`, `kast agent call`,
`kast agent workflow`, workflow package verification, `rename-plan`,
`write-validate`, Copilot package installers, and portable instruction package
installers. If a stale binary exposes them, prefer root `kast setup`, root
`kast ready`, the typed commands above, and update the active Kast binary.
