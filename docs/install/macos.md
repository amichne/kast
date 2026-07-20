---
title: macOS Developer Machine
description: Install and reconcile one matched Kast CLI, JetBrains plugin, and agent-resource bundle.
icon: lucide/apple
---

# macOS Developer Machine

Use this path when you work on a local macOS project with IntelliJ IDEA or
Android Studio. The active Kast CLI owns one matched machine bundle containing
the CLI, JetBrains plugin, skill, and Codex adapter.

## Install The Matched Pair

Quit the IDE first. Kast refuses to replace a loaded plugin.

```console title="Install Kast"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer taps `amichne/kast`, installs the formula, downloads the exact
matching IDEA plugin ZIP, and runs:

```console title="IDE-owned plugin installation"
kast machine activate --idea-plugin <downloaded-zip>
kast machine reconcile
```

Activation atomically selects one strict machine manifest. Reconciliation
verifies every digest, replaces the closed IDE's plugin, and selects the global
agent resources. It installs no LaunchAgent, plist, socket, or background
process.

Open the exact project after installation. The plugin writes revisioned
compatibility metadata for that root.

??? question "What the IDE and agents handle"
    On macOS, workspace setup is owned by the IntelliJ plugin. It prepares
    exact-root compatibility metadata when the project opens. Skills and
    provider adapters remain machine scoped.

## Update And Verify

??? info "Advanced installer controls"
    The `update` and `verify` modes exist for automation, mirrors, and support.
    Pass `--tap` with `--tap-url` for an internal CLI tap and release mirror.

Update the selected machine bundle:

```console title="Update the CLI"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
```

The command installs the updated CLI, downloads its exact plugin, and performs
the same activation/reconciliation transaction.

After opening the exact project, validate the machine bundle, plugin metadata,
backend identity, protocol, and capabilities on demand:

```console title="Verify the active IDEA pair"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- \
  verify
```

This delegates to `kast agent verify --backend idea`. Typed admission rejects
an unsupported pair instead of assuming matching version text is enough.

Use `NONINTERACTIVE=1` only to accept the installer plan in automation. Keep
the IDE closed through reconciliation.

Continue with [how Kast thinks about evidence](../learn/evidence-model.md).
