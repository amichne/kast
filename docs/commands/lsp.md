---
title: LSP Commands
description: Run the Kast Language Server Protocol adapter.
icon: lucide/cable
---

# LSP Commands

The LSP adapter remains available for editor integration:

```console
kast agent lsp --stdio --workspace-root "$PWD"
```

For agent automation, prefer typed `kast agent` commands such as `symbol`,
`diagnostics`, `impact`, and `rename`.
