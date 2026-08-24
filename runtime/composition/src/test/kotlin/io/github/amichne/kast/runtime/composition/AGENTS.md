# Runtime composition test guide

This directory owns composition-level proof that the installed runtime assembly wires every
required target capability and preserves its cross-module contracts.

## Ownership

- Root tests cover assembly, installed adapters, and cross-capability behavior.
- `protocol/` owns protocol-authority durability and structured public projection tests.
- `installed/` and `fixtures/` own their existing installed-runtime and fixture boundaries.

## Verification

Run `./gradlew :runtime:composition:test`; use the exact moved test class first for focused proof.
