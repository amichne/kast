# Kast Agent Instructions

These instruction files are for agent hosts that can load Markdown guidance but
do not load the full Kast skill or repository-local Copilot package.

- `cli.md` explains non-interactive command-line usage.
- `rpc.md` explains the pipe-friendly `kast agent` path and raw RPC fallback.
- `lsp.md` explains the standard LSP adapter contract.

Prefer `kast install copilot` for Copilot repositories and `kast install skill`
for hosts that understand skills. Use this instruction set when the host only
needs portable Markdown operating rules.
