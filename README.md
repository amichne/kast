[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

# Kast

Kast gives agents compiler-backed Kotlin and Gradle context through IntelliJ
IDEA, Android Studio, or the packaged headless backend.

## Install or update

One command installs, replaces, repairs, upgrades, or downgrades Kast. On macOS
it installs the native CLI and matching IDEA plugin; on Linux it installs the
complete headless release:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The bootstrap delegates to `kast setup`. A successful invocation activates the
platform release and receipt under `KAST_HOME` (default
`~/.local/share/kast`). A failed invocation leaves the prior active release usable.
When Codex is installed, the bootstrap independently fast-forwards the public
`amichne/kast-marketplace` and installs `kast@kast`.

For a local bundle:

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

Read the [installation guide](https://kast.michne.com/install/setup/),
[Codex usage guide](https://kast.michne.com/use/codex/), or
[troubleshooting guide](https://kast.michne.com/troubleshoot/).
