# LSP Module Instructions

This directory owns the `kast agent lsp` stdio adapter.

Keep runtime transport, server state, capability routing, JSON-RPC framing,
range/URI conversion, symbol mapping, and tests separated. The LSP adapter must
fail closed when backend capabilities are missing or stale.

Do not mix protocol parsing with Kast semantic request construction unless a
single function is the explicit boundary between the two.
