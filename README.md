# Kast

**Compiler understanding for your coding agent.**

Kast lets your coding agent ask IntelliJ IDEA about your Kotlin Gradle project.
The agent gets resolved declarations, callers, and IDEA diagnostics instead of text matches.
Each answer says whether the search finished, so the agent can tell "none found" from "not fully searched".

## Get started

You need:

- An Apple silicon Mac and a Kotlin Gradle project.
- IntelliJ IDEA 2026.2 or 2026.3 (build `262.*` or `263.*`), with its bundled Kotlin plugin and Java 25 JetBrains Runtime (JBR).
- The Codex, Copilot, or Pi CLI.
- Python 3.12 or 3.13, available as `python3` on your `PATH`.
- For `kast upgrade` and the optional MCP setup: the uv Python package manager, version 0.12.9 or later.

Setup takes three steps.

1. Install Kast from any directory, then restart IntelliJ IDEA so that it loads Kast.

   ```shell
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --developer-latest --skip-codex-plugin
   ```

   `--skip-codex-plugin` skips the installer's Codex prompt; step 2 connects every agent, including Codex.
   If your shell cannot find `kast`, add the directory that the installer prints to your `PATH`, then open a new terminal.

2. Connect your agent. Use `copilot` or `pi` instead of `codex` for those agents.

   ```shell
   kast install codex
   ```

   Open your project in IntelliJ IDEA, and keep IDEA open while you work. Then start a new agent session in the same project.

3. Check the setup from your project root. Replace `DECLARATION_NAME` with a class or function in your project, and look for `"type": "complete"` and that declaration's file in the output.

   ```shell
   kast query --name DECLARATION_NAME --fields name,location --json
   ```

[Set up Kast](https://kast.michne.com/start/) explains each step.
On Linux x86-64, or to run Kast without desktop IDEA, see [Run Kast without desktop IDEA](https://kast.michne.com/reference/installation/#manage-isolated-workspaces).

## Learn more

- [What you can ask](https://kast.michne.com/search/)
- [Help](https://kast.michne.com/troubleshooting/)
- [How Kast works](https://kast.michne.com/explanation/)
- [CLI reference](https://kast.michne.com/reference/cli/)
- [Contribute to Kast](docs/development.md)

Kast uses the [GNU General Public License v3.0 only](LICENSE).
Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
