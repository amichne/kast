# GitHub Copilot package guide

This package is LSP-only.

- `lsp.json` starts `kast agent lsp --stdio`.
- `primitive-manifest.json` packages only the LSP configuration.
- Do not add session hooks, mutation gates, or persisted lifecycle state.
