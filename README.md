# Kast

**See what your Kotlin change affects.**

Kast gives your coding agent source relationships from IntelliJ IDEA's compiler analysis.
Use the native `kast` command to find declarations, inspect callers, and read source in your Kotlin project.

## Get started

Use an Apple silicon Mac and a Kotlin Gradle project.
Use IntelliJ IDEA `262.*` with its bundled Kotlin plugin and Java 25 JBR.
Install uv 0.12.9 or later.
Use Python 3.12 or 3.13.

Install the latest qualified development build from a terminal:

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --developer-latest --skip-codex-plugin
```

You need no checkout or existing `kast` command.
The installer replaces Kast-owned assets and can end active Kast sessions.
Restart IntelliJ IDEA to load the installed Host.

Install CLI guidance for your agent:

```shell
kast install codex
```

Use `copilot` or `pi` instead of `codex` for another agent.
Start a fresh agent session in the same Kotlin repository or worktree.

Give your agent the function's file and name, then ask:

> Use the Kast CLI to find the callers of this function. Show the source locations and explain how they use it.

The persistent Host in IntelliJ IDEA supplies compiler analysis for CLI calls.
The default agent setup supplies CLI guidance. MCP is optional.

[Set up Kast](https://kast.michne.com/start/) ·
[CLI reference](https://kast.michne.com/reference/cli/) ·
[Help](https://kast.michne.com/troubleshooting/)

## More

- [Example questions](https://kast.michne.com/search/).
- [A visual explanation of value flow](https://kast.michne.com/explanation/value-journey/).
- [Technical reference](https://kast.michne.com/reference/).
- [Contribute to Kast](docs/development.md).

Kast uses the [GNU General Public License v3.0 only](LICENSE).
Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
