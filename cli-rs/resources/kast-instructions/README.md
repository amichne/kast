# Kast Agent Instructions

These instruction files are for agent hosts that can load Markdown guidance but
do not load the full Kast skill or repository-local Copilot package. They assume
the `kast` binary that installed them is available to the agent.

- `cli.md` explains non-interactive command-line usage.
- `tools.md` maps common agent tasks to portable `kast agent` commands.
- `lsp.md` explains the standard LSP adapter contract.

On macOS, repository guidance and invocation metadata are prepared by the
IntelliJ plugin after the workspace opens. On non-macOS headless/server hosts,
prefer `kast setup --dry-run` when the host or repository should inspect the
selected guidance target before writing files.
