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

Restart IDEA after installation. The installer offers to register Kast with
Codex; other MCP clients and the included tool RPC adapters can use the same
installation. See [install and connect](https://kast.michne.com/start/) for
client setup and options.

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
