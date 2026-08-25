# Exact symbol IntelliJ package guide

This directory owns K2-backed exact lookup and selector resolution. Follow
[the production package guide](../AGENTS.md) and the symbol IntelliJ module guide it names.

## Local scope

- Resolve and revalidate one exact declaration inside a request-local K2 analysis session.
- Detach canonical compiler identity before the analysis session ends.
- Do not issue selector authority from names, offsets, PSI runtime types, or display projections.
