---
title: Install And Repair Commands
description: Install repository guidance and repair managed install state.
icon: lucide/wrench
---

# Install And Repair Commands

## Repository Setup

On macOS, the IntelliJ plugin prepares repository guidance and workspace
metadata when the project opens. `kast setup` fails closed there so the CLI
cannot create partial runtime/resource state.

On non-macOS headless/server installs, use `kast setup` once per repository
where agents should discover Kast guidance. Setup installs only:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region

```console
kast setup --dry-run --workspace-root "$PWD"
kast setup --workspace-root "$PWD"
kast setup --context-file "$PWD/cli-rs/AGENTS.md" --force
```

If no supported context file exists, setup creates ignored `AGENTS.local.md`.
Pass `--context-file` for an explicit `AGENTS.md`, `CODEX.md`, `CLAUDE.md`,
`.github/copilot-instructions.md`, or `AGENTS.local.md` target.

JSON dry-runs report `skillTarget`, `agentsMdTargets`, and `installCommand`.

## Readiness

`kast ready` reports readiness and does not mutate install state:

```console
kast ready --workspace-root "$PWD"
kast ready --for machine --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
kast ready --for release --workspace-root "$PWD"
```

## Repair

`kast repair` is the explicit repair gate. Without `--apply`, it plans only.

```console
kast repair --workspace-root "$PWD"
kast repair --workspace-root "$PWD" --apply
kast repair --for machine --workspace-root "$PWD" --apply
```

Use `kast developer inspect paths` when you only need the manifest-backed path
model.

```console
kast developer inspect paths
kast --output json developer inspect paths
```

## Out Of Scope In V1

Repository setup does not install Copilot package files, portable Markdown
instruction packages, session hooks, generated catalog copies, or workflow helper
assets.
