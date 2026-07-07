# LSP Module Instructions

This directory owns the `kast agent lsp` stdio adapter.

Keep runtime transport, server state, capability routing, JSON-RPC framing,
range/URI conversion, symbol mapping, and tests separated. The LSP adapter
reports missing or unsupported backend capabilities as typed errors.

Protocol parsing and Kast semantic request construction meet at explicit
boundary functions.
