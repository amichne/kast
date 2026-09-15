# Kast

Kast gives coding agents compiler-grounded search, diagnostics, and approved
source changes for the Kotlin project open in IntelliJ IDEA. Find declarations
and follow their relationships with the compiler’s identities and evidence.

[Documentation](https://kast.michne.com/) ·
[Search guide](https://kast.michne.com/search/) ·
[Troubleshooting](https://kast.michne.com/troubleshooting/)

## Get started

You need macOS on Apple silicon, a Kotlin Gradle repository, IntelliJ IDEA
`262.*` with its bundled Kotlin plugin and Java 25 JBR, and `codex` on your `PATH`.
The Codex integration is a preview; full Desktop compatibility remains
[unqualified](https://kast.michne.com/reference/compatibility/).

1. Install Kast and its IDEA plugin:

   ```shell
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
   ```

   The installer reports the detected IntelliJ version, verifies its matching
   plugin, explains the app server, and asks whether to enable its per-user
   login LaunchAgent. If no matching IDEA plugin exists, it installs nothing.
   For automation, append `-- --no-interactive`; the LaunchAgent then remains
   disabled unless `KAST_ENABLE_LAUNCHD=1` is explicitly set.

   Choose other absolute user-owned locations with `--install-root` and
   `--bin-dir`. If a selected command path is occupied, the installer lists it
   and asks before removal. Automation must opt in with `--clean-collisions`.

2. Restart IDEA, open your repository, and wait for Gradle import and indexing.
   Save edited files. Add the selected command directory to `PATH` if needed.

3. Connect from that repository:

   ```console
   cd /path/to/kotlin-repository
   kast codex
   ```

4. Ask your agent: **“Use Kast to find the class OrderService.”** Replace the
   name with a class in your project. Check the returned status and matches.

See [Install and connect](https://kast.michne.com/start/) for IDE selection,
Desktop setup, other harnesses, and uninstall instructions.

## Work with Kotlin

| Task | Guide |
| --- | --- |
| Find classes, functions, or properties | [Search declarations](https://kast.michne.com/search/) |
| Follow callers and implementations | [Query pipelines](https://kast.michne.com/query-pipelines/) |
| Add a declaration with an approved preview | [Change source](https://kast.michne.com/change/) |
| Connect another agent host | [Harness integration](https://kast.michne.com/agent-harnesses/) |

Reads use saved, indexed IDEA state. A **complete** answer covers its declared
scope; a **qualified** answer carries limits; a **rejected** request provides no
successful semantic result. Keep those limits and returned `ref` values
when following up. See [Read a response](https://kast.michne.com/reference/responses/).

## Develop Kast

Use Java 25 or newer and the Python version in [`.python-version`](.python-version).

```shell
./gradlew build
./gradlew assembleRelease
```

To build and try this checkout in an isolated Bash or Zsh session:

```shell
source "$(./install.sh --local session)"
```

Start with the [development guide](docs/development.md) for persistent local
installation and native acceptance, or the [knowledge base](knowledge/index.md)
for architecture and source ownership. `kast knowledge KastCli` searches the
installed documentation for Kast’s own public declarations without an open IDE.

## Security and license

Report vulnerabilities through [GitHub private vulnerability
reporting](https://github.com/amichne/kast/security/advisories/new).
Kast is available under the [MIT License](LICENSE).
