# Kast

**Compiler understanding for your coding agent.**

Kast extends coding agents working on Kotlin Gradle projects with IntelliJ IDEA's compiler analysis.
Your agent can resolve declarations, types, and source relationships instead of inferring them from text alone.

Keep working naturally. Ask about your code. Build a feature. Fix a bug. Refactor.
Kast brings compiler-grounded evidence into that work.

## Get started

Use an Apple silicon Mac and a Kotlin Gradle project.
Use IntelliJ IDEA `262.*` or `263.*` with its bundled Kotlin plugin and Java 25 JBR.
Use Python 3.12 or 3.13.
For upgrades or optional MCP, install uv 0.12.9 or later.

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

Continue your work with the agent. The installed guidance covers Kast's native CLI; MCP is optional.
The persistent Host in IntelliJ IDEA supplies compiler analysis for the same project.

Kast results keep their source scope, completion status, and evidence limits.
[Understand the evidence](https://kast.michne.com/explanation/) for the completion contract and its limits.

[Set up Kast](https://kast.michne.com/start/) ·
[CLI reference](https://kast.michne.com/reference/cli/) ·
[Help](https://kast.michne.com/troubleshooting/)

For a private Host on macOS ARM64 or Linux x86-64, use the [isolated runtime archive](https://kast.michne.com/reference/installation/#isolated-runtime).

## More

- [Agent capabilities](https://kast.michne.com/search/).
- [A visual explanation of value flow](https://kast.michne.com/explanation/value-journey/).
- [Technical reference](https://kast.michne.com/reference/).
- [Contribute to Kast](docs/development.md).

Kast uses the [GNU General Public License v3.0 only](LICENSE).
Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
