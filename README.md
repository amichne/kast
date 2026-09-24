# Kast

Kast gives coding agents compiler-grounded search, diagnostics, and controlled
source changes for the Kotlin project open in IntelliJ IDEA. Find declarations
and follow their relationships with the compiler’s identities and evidence.

[Documentation](https://kast.michne.com/) ·
[Search guide](https://kast.michne.com/search/) ·
[Troubleshooting](https://kast.michne.com/troubleshooting/)

## Get started

You need macOS on Apple silicon, a Kotlin Gradle repository, IntelliJ IDEA
`262.*` with its bundled Kotlin plugin and Java 25 JBR, and a compatible MCP client.

1. Install Kast and its IDEA plugin:

   ```shell
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
   ```

   The installer reports the detected IntelliJ version, verifies its matching
   plugin, registers Kast's MCP server with Codex, and enables the app-server suite and its per-user login
   LaunchAgent. If no matching IDEA plugin exists, it installs nothing.
   Installation never prompts. It uses `${XDG_DATA_HOME:-$HOME/.local/share}/kast`
   without publishing a `kast` command on `PATH`. An upgrade retires only
   command links owned by a previous Kast installation.
   An ordinary upgrade keeps the current release selected when the prior daemon
   still has active work or its state cannot be proved. The installer reports
   the blocker; rerun it after that work settles.

   To rebuild a damaged installation, append `-- --force`. This retires its
   services, resets managed sockets and state, and restages the plugin.
   Source workspaces are preserved;
   workspace enrollment is rebuilt. Use `-- --force --dry-run` to preview.
   Previous payloads and recovery metadata are moved aside under `.replaced-*`.

2. Restart IDEA to load the plugin and save edited files.

3. Start ordinary Codex from the repository:

   ```console
   cd /path/to/kotlin-repository
   codex
   ```

   Kast's MCP process discovers the exact Gradle root and prepares its IDEA
   project on the first valid request. No per-repository registration is
   required. Desktop UI discovery remains unverified.

4. Ask your agent: **“Use Kast to find the class OrderService.”** Replace the
   name with a class in your project. Check the returned status and matches.

See [Install and connect](https://kast.michne.com/start/) for IDE selection,
Desktop setup, other harnesses, and uninstall instructions.

The Kast MCP session starts preparing the exact repository or worktree on its
first valid request: any 2026-07-28 request, commonly `server/discover`, or
`initialize` for handshake clients. It reuses the selected IDEA or launches it in the background, then
opens and imports that project. Compiler-backed calls join the same preparation
and wait for readiness. You do not need to manage project lifecycle commands.
Kast asks for help only when setup encounters a user-owned decision such as project
trust or unsaved documents.

## Work with Kotlin

| Task | Guide |
| --- | --- |
| Find classes, functions, or properties | [Search declarations](https://kast.michne.com/search/) |
| Follow callers and implementations | [Query pipelines](https://kast.michne.com/query-pipelines/) |
| Add a declaration in one call | [Change source](https://kast.michne.com/change/) |
| Connect another agent host | [Harness integration](https://kast.michne.com/agent-harnesses/) |

Reads use saved, indexed IDEA state. In the direct MCP result envelope,
**complete** means the requested scope was exhausted; **partial** includes a
stop reason and does not prove absence. The original canonical document remains
in the result evidence, with its exact `ref` values and detailed qualifications.
Machine-readable results include schema-validated `structuredContent`. The first
text item summarizes semantic reads; the canonical document also remains as text
for clients that do not expose structured results to the model.
Keep those limits and returned `ref` values
when following up. See [Read a response](https://kast.michne.com/reference/responses/).

For an installation check, call `health_check` with `{}` after preparation
finishes. It reports the exact workspace and IDEA readiness without asserting
semantic correctness or repeating the tool inventory. MCP `tools/list` discovers
callable tools. During launch or import it can still report an unavailable
host. Call `validate_workspace` with an explicit declaration, relation, or diagnostic path
to run read-only semantic probes. Each stage reports `passed`, `failed`, or
`unverified`; an incomplete read never passes a probe.
Hosts supporting MCP Apps can render these five probe outcomes from
`ui://kast/validation`. Other hosts receive the text summary and structured result.

## Develop Kast

Use Java 25 or newer and the Python version in [`.python-version`](.python-version).

Start testing with one named behavior and its smallest required dependencies.
Run the production rule with explicit inputs and script only external observations;
use real filesystem, compiler or native fixtures when the assertion needs their
authority. See the [testing approach](docs/development.md#test-one-behavior-at-a-time)
for focused checks and when to widen verification.

```shell
./gradlew build
./gradlew assembleRelease
```

To build and try this checkout in an isolated Bash or Zsh session:

```shell
source "$(./packaging/install-checkout.sh session --idea-home "/Applications/IntelliJ IDEA.app")"
```

Start with the [development guide](docs/development.md) for testing, persistent local
installation and native acceptance, or the [knowledge base](knowledge/index.md)
for architecture and source ownership.

## Security and license

Report vulnerabilities through [GitHub private vulnerability
reporting](https://github.com/amichne/kast/security/advisories/new).
Kast is available under the [MIT License](LICENSE).
