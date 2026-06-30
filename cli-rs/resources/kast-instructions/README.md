# Kast Agent Instructions

These instruction files are for agent hosts that can load Markdown guidance but
do not load the full Kast skill or repository-local Copilot package. They assume
the `kast` binary that installed them is available to the agent.

- `cli.md` explains non-interactive command-line usage.
- `tools.md` maps common agent tasks to portable `kast agent` commands.
- `tools.md` explains the pipe-friendly `kast agent call` path for catalog methods.
- `lsp.md` explains the standard LSP adapter contract.

Prefer `kast setup --dry-run` when the host or repository should inspect the
selected guidance target and runtime warmup before writing files. Use
`kast setup --no-open-ide` in automation when a human terminal may be attached.
