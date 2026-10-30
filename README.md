# Kast

**See what your Kotlin change affects.**

Kast gives your coding agent source relationships from IntelliJ IDEA's compiler analysis.
Connect it to Codex, Copilot, or Pi, then ask about the code you already work on.

## Get started with Codex

Use an Apple silicon Mac, a Kotlin Gradle project, and IntelliJ IDEA `262.*`
with its bundled Kotlin plugin and Java 25 JBR.
Install Codex CLI and Python 3.9 or later, available as `python3`.

Install the stable release and its Codex plugin:

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --install-codex-plugin
```

Restart IntelliJ IDEA. Start a fresh Codex session in the same Kotlin repository or worktree.
Review and trust the Kast session context hook.

Give your agent the function's file and name, then ask:

> Use Kast to find the callers of this function. Show the source locations and explain how they use it.

Kast prepares the project as needed. The first query can wait for Gradle import and indexing.
Continue your task with the returned source locations.

[Set up Copilot or Pi](https://kast.michne.com/start/) ·
[Help](https://kast.michne.com/troubleshooting/)

## More

- [Example questions](https://kast.michne.com/search/).
- [A visual explanation of value flow](https://kast.michne.com/explanation/value-journey/).
- [Technical reference](https://kast.michne.com/reference/).
- [Contribute to Kast](docs/development.md).

Kast uses the [GNU General Public License v3.0 only](LICENSE).
Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
