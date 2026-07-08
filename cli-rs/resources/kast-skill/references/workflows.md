# Kast command ownership

Use this file when a task needs install-state ownership, readiness boundaries, or
repeatable semantic command choices. It documents the v1 public dialect; generated
RPC catalogs and protocol samples remain internal contracts.

## Install and readiness

On macOS, the IntelliJ plugin prepares workspace guidance and metadata when the
project opens. `kast setup` fails closed there so agents cannot create partial
runtime/resource state.

On non-macOS headless/server installs, `kast setup` installs or repairs only
the repository agent assets:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected repo context file

For IDEA plugin install or repair, use the typed machine command:

```console
kast developer machine plugin
```

Setup does not install Copilot packages, portable Markdown instruction
packages, session hooks, generated catalog copies, or workflow helper assets.

Use these health boundaries:

| Question | Command |
| --- | --- |
| workspace/runtime state | `kast status --workspace-root "$PWD"` |
| readiness for a task | `kast ready --for agent --workspace-root "$PWD"` |
| semantic backend capability | `kast agent verify --workspace-root "$PWD"` |
| daemon lifecycle | `kast runtime status --workspace-root "$PWD"` |
| install-state repair plan | `kast repair --for agent --workspace-root "$PWD"` |
| install-state repair apply | `kast repair --for agent --workspace-root "$PWD" --apply` |

`ready` is read-only. `repair` is plan-only unless `--apply` is present.

## Semantic command patterns

Use compiler identity and typed selectors instead of offsets:

| Need | Command |
| --- | --- |
| symbol lookup | `kast agent symbol --query EventBean --workspace-root "$PWD"` |
| references | `kast agent symbol --query EventBean --references --workspace-root "$PWD"` |
| callers | `kast agent symbol --query process --callers incoming --workspace-root "$PWD"` |
| diagnostics | `kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"` |
| source-index impact | `kast agent impact --symbol com.example.EventBean --workspace-root "$PWD"` |
| rename plan | `kast agent rename --symbol com.example.EventBean --new-name DomainEvent --workspace-root "$PWD"` |
| rename apply | `kast agent rename --symbol com.example.EventBean --new-name DomainEvent --apply --workspace-root "$PWD"` |

Use `agent symbol --query <name>` for lookup. Reserve
`--symbol <fq-name>` for compiler identity on commands that require a resolved
target.

## Removed surfaces

The following commands are intentionally not public v1 assets: `kast agent up`,
`kast agent ready`, `kast agent setup`, `kast agent tools`, `kast agent call`,
`kast agent workflow`, workflow package verification, `rename-plan`,
`write-validate`, Copilot package installers, and portable instruction package
installers. If a stale binary exposes them, prefer root `kast setup`, root
`kast ready`, the typed commands above, and update the active Kast binary.
