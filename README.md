# Kast

**See where your Kotlin changes lead.**

Your agent can read a function. Kast lets it trace the connections through your
Kotlin project: which function a call selects, where a value goes, and which
code depends on it.

## Can my agent already do that?

Ask it where a value goes after a helper returns it inside an object, through a
callback, and into a list. Then change the callback to return a different value.
The names still match. The answer changes.

Kast gives your agent compiler-backed connections and source locations for that
investigation. It also reports where the evidence stops.

[![Kast traces one value through a helper, a field, and a list. A separate value does not share the route.](docs/public/images/value-journey.svg)](https://kast.michne.com/explanation/value-journey/)

[**Try the interactive example →**](https://kast.michne.com/explanation/value-journey/)

## Use it on your code

> Use Kast to trace this function's result. Show where it goes and where the evidence stops.

> Use Kast to find the callers of this function before we change it.

> Use Kast to inspect the diagnostics in this directory.

Kast connects Codex, Copilot, or Pi to IntelliJ IDEA. Your agent can find exact
code, trace relationships, inspect problems, and make targeted source changes.

## Get started

You need an Apple silicon Mac, a Kotlin Gradle project, and IntelliJ IDEA
`262.*` with its bundled Kotlin plugin and Java 25 JBR.
For Codex setup, you also need `codex` and Python 3.9 or later on `PATH`.

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

Accept the default Codex connection. Restart IDEA and Codex.
Select your Kotlin repository or worktree, then ask:

> Use Kast to find a Kotlin function in this workspace. Show its signature and source location.

[Installation guide](https://kast.michne.com/start/) ·
[Copilot and Pi](https://kast.michne.com/agent-harnesses/) ·
[Troubleshooting](https://kast.michne.com/troubleshooting/)

## Explore

- [Questions to ask your agent](https://kast.michne.com/search/).
- [Try a small exercise](https://kast.michne.com/tutorials/first-query/).
- [Technical reference](https://kast.michne.com/reference/).
- [Contribute to Kast](docs/development.md).

Kast describes connections in source. Your project's build and tests establish behavior after a change.

Kast uses the [GNU General Public License v3.0 only](LICENSE).
Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
