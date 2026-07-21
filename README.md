[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

# Kast

Kast gives Codex compiler-backed Kotlin and Gradle context from the IntelliJ
IDEA or Android Studio project already open on your Mac. You describe the work
to Codex; Kast stays behind that interface.

## Install the workstation bundle

Install Codex and IntelliJ IDEA or Android Studio, quit the IDE, then run the
single workstation installer:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer selects one matched CLI and IDEA plugin, fast-forwards the public
`amichne/kast-marketplace` marketplace, and installs `kast@kast`. It creates no
global Kast skill and starts no background service.

For native IDEA update discovery, add this URL under **Settings → Plugins →
Manage Plugin Repositories**:

```text
https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml
```

Open the exact project or worktree in the IDE after installation, then start a
new Codex task. The IDEA plugin prepares that root; the Codex plugin routes
Kotlin inspection and edits through Kast automatically.

## Update

Quit the IDE and rerun the same installer in update mode:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
```

If IDEA applies a plugin update from the feed independently, rerun the
installer before the next task so the CLI and IDEA plugin return to a matched
bundle. The Codex plugin continues to track the marketplace's `main` branch.

Read the [workstation install guide](https://kast.michne.com/install/macos/),
[Codex usage guide](https://kast.michne.com/use/codex/), or
[troubleshooting guide](https://kast.michne.com/troubleshoot/).
