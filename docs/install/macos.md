---
title: macOS Developer Machine
description: Install a Homebrew CLI and GitHub-hosted JetBrains plugin, then verify their compatibility.
icon: lucide/apple
---

# macOS Developer Machine

Use this path when you work on a local macOS project with IntelliJ IDEA or
Android Studio. Homebrew owns the Kast CLI; JetBrains owns the installed plugin
files and applies updates from Kast's GitHub Release feed.

## Install The Matched Pair

Quit the IDE first. JetBrains' command-line plugin installer cannot update a
running IDE.

```console title="Install Kast"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer taps `amichne/kast`, installs the formula, and runs the installed
binary's `kast repair --for machine --apply`. Repair writes a strict CLI-only
receipt at `~/Library/Application Support/Kast/homebrew-install.json`.

For an absent plugin, the installer then reads the installed binary's version
and asks a standard IntelliJ IDEA or Android Studio launcher to run:

```console title="IDE-owned plugin installation"
idea installPlugins io.github.amichne.kast \
  https://github.com/amichne/kast/releases/download/v<version>/updatePlugins.xml
```

This is a normal JetBrains command, not a direct write or profile link. It does
not replace a plugin that is already installed. For a Toolbox or nonstandard
installation, pass the executable explicitly with `--ide-launcher
/path/to/idea` (or `studio`). If no launcher is found, the installer prints the
exact `kast-idea-v<version>.zip` URL; install that ZIP with **Install Plugin from Disk**.

Open the exact project after installation. The plugin writes revisioned
compatibility metadata for that root.

## Enable Native Update Discovery

In **Settings | Plugins**, add this custom plugin repository once:

```text
https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml
```

The IDE can then discover GitHub-hosted updates. A compatible dynamic plugin
update may apply without restarting, but JetBrains can still require a restart
when unload is refused. Kast does not bypass that fallback.

??? question "What the IDE and agents handle"
    On macOS, workspace setup is owned by the IntelliJ plugin. It prepares
    exact-root guidance and compatibility metadata when the project opens.
    Agents consume that metadata through typed commands; the installer does not write workspace state or plugin directories.

## Update And Verify

??? info "Advanced installer controls"
    The `update` and `verify` modes exist for automation, mirrors, and support.
    Pass `--tap` with `--tap-url` for an internal CLI tap. During initial
    installation, use `--ide-launcher` for a nonstandard IDE executable.

Update the Homebrew-owned CLI:

```console title="Update the CLI"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
```

The command prints the plugin release expected by that CLI and its exact ZIP
fallback. JetBrains' headless `installPlugins` command skips an already
installed plugin, so the script does not pretend it can force the plugin half
of the update. Apply the update from the enrolled custom repository in
**Settings | Plugins**, or enable JetBrains' automatic plugin updates.

After opening the exact project, validate the Homebrew receipt, installed
plugin metadata, backend identity, protocol, and capabilities on demand:

```console title="Verify the active IDEA pair"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- \
  verify
```

This delegates to `kast agent verify --backend idea`. If the IDE updates from
the custom feed before Homebrew is upgraded, update the CLI too; typed
admission rejects an unsupported pair instead of assuming matching version
text is enough.

## Legacy Cutover Cleanup

The 0.13.0 repair path recognizes the old joint receipt only for one-shot
migration to schema 2. It may also back up and unlink an exact legacy
`Caskroom/kast-plugin/<version>/backend-idea` symlink. Regular files,
directories, relative links, traversing links, and any unrecognized state are
preserved. Cleanup never creates a profile or replacement link.

Use `NONINTERACTIVE=1` only to accept the installer plan in automation. Keep
the IDE closed while the initial `install` delegates to `installPlugins`.

Continue with [how Kast thinks about evidence](../learn/evidence-model.md).
