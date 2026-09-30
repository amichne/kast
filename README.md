# Kast

Kast lets coding agents query the Kotlin project in IntelliJ IDEA. Its results
carry compiler-backed symbol references, source locations, and coverage so an
agent can tell what the answer proves.

## Install

Kast currently supports macOS on Apple silicon, a Kotlin Gradle repository,
and IntelliJ IDEA `262.*` with its bundled Kotlin plugin and Java 25 JBR.

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer publishes the native `kast` management command. If its directory
is not on `PATH`, it prints the absolute command path and the directory to add.
When `XDG_CONFIG_HOME` is nonempty, the command is `$XDG_CONFIG_HOME/kast`;
otherwise it is `$HOME/.local/bin/kast`. Upgrades retain the original location.
Use `kast status` to inspect the installation and `kast connect` to choose
Codex, Copilot, or Pi. Restart IDEA after installation and restart a harness
after connecting it. See [install and connect](https://kast.michne.com/start/).

Installer output shows progress and readable failure reasons. Pass `--verbose`
after the downloaded command's `--` separator to include structured diagnostic
reports.

Use `kast upgrade` to install the latest release on the selected channel.
The upgrade interrupts existing calls and sessions. Kast removes prior versions
that pass installation admission.
For older Kast processes, login items, plugin backups, install directories, or
manifest-listed anchors whose ownership is incomplete, the installer shows
each exact item and asks before removing it. Answer `yes` for an individual
item; every other answer keeps it. A noninteractive upgrade keeps uncertain
items and reports them.

Use `kast uninstall` to stop Kast-owned services and remove the owned
installation and registrations.

## Ask a question

Start your agent from the Kotlin repository or worktree, then ask:

> Use Kast to query declarations in this workspace. Show one compiler-backed
> symbol with its signature, source location, exact `ref`, and the result's
> coverage. If there is no proven match, report the limit or failure.

The query works without knowing a project-specific class name. You can pass
the returned `ref` to a later Kast read. See [query examples](https://kast.michne.com/search/)
and [how Kast works](https://kast.michne.com/concepts/architecture/).

## Develop

Use Java 25 or newer and the Python version in [`.python-version`](.python-version).

```shell
./gradlew build
./gradlew assembleRelease
```

The [development guide](docs/development.md) covers local installation and
testing. The [knowledge base](knowledge/index.md) maps architecture to source.

Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
Kast is under the [MIT License](LICENSE).
