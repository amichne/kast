# Exact symbol contract package guide

This directory owns public exact-symbol operations and selector policy. Follow
[the production package guide](../AGENTS.md) and the symbol contract module guide it names.

## Local scope

- Exact reads consume issued `SymbolSelector` authority. Do not reconstruct authority from names,
  locations, or display projections.
- Keep request, success, and rejection states host-neutral and closed.
