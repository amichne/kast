---
title: macOS Developer Machine
description: Install the Homebrew CLI and signed JetBrains plugin with separate authorities.
icon: lucide/apple
---

# macOS Developer Machine

Use this path when you work on a local macOS project with IntelliJ IDEA or Android Studio.
On macOS, Homebrew owns the Kast CLI and JetBrains owns the signed plugin.
Neither authority installs or repairs the other.

## Install The CLI

```console title="Install the Homebrew CLI"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer taps `amichne/kast`, installs the formula, and runs the installed
binary's `kast repair --for machine --apply`. Repair writes a strict CLI-only
receipt at `~/Library/Application Support/Kast/homebrew-install.json`. It does
not inspect, close, or mutate an IDE.

Normal developer use does not require running readiness, repair, or setup commands by hand.

## Install The Signed Plugin

1. Download the signed `kast-idea-v<version>.zip` release asset and its
   published certificate fingerprint.
2. In IntelliJ IDEA or Android Studio, choose **Install Plugin from Disk** and
   select the ZIP.
3. Add the published signing certificate and custom plugin repository in the
   IDE. Kast never enrolls trust or repositories automatically.
4. Reopen the exact project. The plugin writes revisioned compatibility
   metadata for that root.

JetBrains owns subsequent plugin updates. Homebrew upgrades do not install or
link the plugin, and plugin updates do not replace the CLI.

??? question "What the IDE and agents handle"
    On macOS, workspace setup is owned by the IntelliJ plugin. The signed
    plugin prepares exact-root guidance and compatibility metadata when the
    project opens. Agents consume that metadata through typed commands; the
    installer does not write workspace state.

## Update And Verify

??? info "Advanced installer controls"
    The `update` and `verify` modes exist for automation, mirrors, and support.
    Pass `--tap` with `--tap-url` for an internal CLI tap. They never enroll
    plugin trust or mutate an IDE.

```console title="Update the CLI"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
```

Update the plugin through JetBrains and reopen the exact project. The first
semantic workflow performs exact-root verification. If compatibility fails,
update both authorities as needed, reopen the exact
project, and let the plugin refresh `.kast/setup/workspace.json`.

## Legacy Cutover Cleanup

The 0.13.0 repair path recognizes the old joint receipt only for one-shot
migration to schema 2. It may also back up and unlink an exact legacy
`Caskroom/kast-plugin/<version>/backend-idea` symlink. Regular files,
directories, relative links, traversing links, and any unrecognized state are
preserved. Cleanup never creates a profile or replacement link.

Use `NONINTERACTIVE=1` only to accept the installer plan in automation. An IDE
needs to be closed only when repair has proved that an owned legacy symlink
will actually be removed.

Continue with [how Kast thinks about evidence](../learn/evidence-model.md).
