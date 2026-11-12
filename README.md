# Kast

**Compiler understanding for your coding agent.**

Kast gives your coding agent a private IntelliJ analysis runtime for your Kotlin Gradle project.
The agent gets resolved declarations, callers, and IDEA diagnostics instead of text matches.
Each answer says whether the search finished, so the agent can tell "none found" from "not fully searched".

## Get started

You need:

- An Apple silicon Mac or Linux x86-64 machine and a Kotlin Gradle project.
- The Codex, Copilot, or Pi CLI.

Kast supplies its runtime. You do not need desktop IDEA, Python, or uv.
This branch documents the managed-runtime contract; native acceptance and publication are not yet complete.

Setup takes three steps.

1. Install Kast from any directory.

   ```shell
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --channel preview
   ```

   The installer selects one complete build. Step 2 connects your agent.
   If your shell cannot find `kast`, add the directory that the installer prints to your `PATH`, then open a new terminal.

2. Connect your agent. Use `copilot` or `pi` instead of `codex` for those agents.

   ```shell
   kast install codex
   ```

   Start a new agent session in your project. Session hooks are passive; they do not start the runtime or grant trust.

3. Query from a project root whose build code you trust. The first explicit semantic request grants trust to that exact root and starts its private Host. Replace `DECLARATION_NAME` with a class or function in your project.

   ```shell
   kast query --name DECLARATION_NAME --fields name,location --json
   ```

   Look for `"type": "complete"` and that declaration's file. A first query can take several minutes for Gradle import and indexing.

[Set up Kast](https://kast.michne.com/start/) explains each step.
Queries use installed runtime bytes without contacting release servers. Your Gradle build can still need network access.

## Learn more

- [What you can ask](https://kast.michne.com/search/)
- [Help](https://kast.michne.com/troubleshooting/)
- [How Kast works](https://kast.michne.com/explanation/)
- [CLI reference](https://kast.michne.com/reference/cli/)
- [Contribute to Kast](docs/development.md)

Kast uses the [GNU General Public License v3.0 only](LICENSE).
Report security issues through [private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
