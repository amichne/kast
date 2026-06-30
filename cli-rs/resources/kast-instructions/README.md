# Kast Agent Instructions

These instruction files are for agent hosts that can load Markdown guidance but
do not load the full Kast skill or repository-local Copilot package. They assume
the `kast` binary that installed them is available to the agent.

- `cli.md` explains non-interactive command-line usage.
- `tools.md` maps common agent tasks to portable `kast agent` commands.
- `lsp.md` explains the standard LSP adapter contract.

Prefer `kast agent setup auto --dry-run` when the host or repository should
choose the package shape from configured preferences and existing resource
roots. Pin the harness only when it is known: `copilot` for the repository
Copilot package, `skill` for skill-aware hosts, and `instructions` for portable
Markdown operating rules.
