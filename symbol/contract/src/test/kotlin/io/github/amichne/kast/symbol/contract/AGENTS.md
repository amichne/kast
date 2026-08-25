# Symbol contract test package guide

This directory owns executable contract examples for symbol requests, selectors, identities, and
relation facts. Follow [the module guide](../../../../../../../../../AGENTS.md) for the production
boundary and verification ladder.

## Local scope

- Test canonical identity determinism and collision resistance with semantically distinct inputs.
- Keep tests host-neutral. IntelliJ-backed behavior belongs in adapter modules.
- Assert closed rejection data rather than exception text or sentinel values.
