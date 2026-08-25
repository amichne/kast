# `symbol/contract` production package guide

This directory owns host-neutral symbol contracts and canonical detached identities. Follow
[the module guide](../../../../../../../../../AGENTS.md) for the contract boundary, invariants, and
verification.

## Local scope

- Keep compiler identity finite, deterministic, and detached from IntelliJ or K2 lifetime.
- Refine native compiler declarations through `CanonicalCompilerSignature` before issuing a
  `CompilerSymbolIdentity`.
- Represent expected canonicalization failures as a closed typed result.
