---
title: macOS Developer Machine
description: Install the Homebrew binary and JetBrains plugin, then verify a repository.
icon: lucide/apple
---

# macOS Developer Machine

Use this path when you work on a local macOS repository with IntelliJ IDEA or
Android Studio. The machine install places the global `kast` binary and the
version-coupled JetBrains plugin on the machine; the plugin prepares repository
metadata when the project opens.

## Install The Machine Distribution

Run the root installer from the repository where agents should use Kast. The
current directory is the default workspace root.

```console title="Install Kast on macOS"
cd /path/to/your/repository
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
open .
```

The installer is macOS-only. It uses Homebrew to install the global `kast`
binary, installs or refreshes the matching IDEA or Android Studio plugin, and
fails before mutation for unsupported hosts, unknown commands, invalid flags,
invalid tap values, invalid tap URLs, or missing workspace roots.

!!! note "Mutation gate"
    Mutating installer commands explain the planned Homebrew and plugin actions
    before changing the machine. Use `NONINTERACTIVE=1` only when automation has
    already accepted that plan.

## Refresh Or Verify The Install

Use the explicit update command when the hidden Homebrew path should be
refreshed or local JetBrains profile links need repair.

```console title="Refresh and verify"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update --workspace-root "$PWD"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- verify --workspace-root "$PWD"
```

The default Homebrew tap is `amichne/kast`. Pass both `--tap` and `--tap-url`
when a mirror lives on a custom Git host.

```console title="Install from an internal Homebrew tap"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- install \
  --tap internal/kast \
  --tap-url https://git.example.com/internal/homebrew-kast.git \
  --workspace-root "$PWD"
```

## Open The Repository

On macOS, workspace setup is owned by the IntelliJ plugin. Open the repository
after the installer refreshes the plugin. The plugin writes:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected context file
- `.kast/setup/workspace.json` with plugin-prepared invocation metadata

The CLI does not install skill-only, runtime-only, Copilot package, portable
instruction, session hook, generated catalog, workflow helper, or resource-only
workspace setup on macOS. If prior Kast-managed files are not required or
recognized by the incoming plugin version, the plugin backs them up and removes them
from the active setup path.

## Verify Readiness

Run readiness before semantic commands. Readiness does not mutate install
state.

```console title="Check readiness"
kast ready --for agent --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
kast --output json ready --for agent --workspace-root "$PWD"
```

Plan repair before applying it when readiness reports drift.

```console title="Plan and apply repair"
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

Continue with the [first semantic workflow](../learn/first-semantic-workflow.md)
after readiness reports the repository and backend state clearly.
