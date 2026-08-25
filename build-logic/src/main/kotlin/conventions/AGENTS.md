# Convention script guide

This directory owns precompiled convention plugins whose `package kast` declaration preserves the
public `kast.*` plugin IDs. Follow [the build-logic guide](../../../../AGENTS.md) for shared plugin
boundaries and verification.

## Local scope

- Keep each script below the repository line limit.
- Keep repository-specific PR gate wiring in the `pr633-*` scripts and reusable task types under
  `support/pr633`.
- Keep VFS-passive program wiring in `vfs-passive-delivery.gradle.kts` and its typed authority and
  task types under `support/delivery`. The KVP-001 negative and green command names are public
  delivery-program contracts; keep their registration derived from the canonical Kotlin program.
  `verifyKastVfsPassiveAuthority` must depend on `generateKastVfsPassiveAuthority` so the GREEN
  command emits the authority ledger and contradiction projection before re-admitting their bytes
  and writing the verification report under `build/reports/delivery`.
  Keep live gate and completion receipts under `build/reports/delivery/receipts`; a checked-in
  receipt cannot bind the commit that contains its own bytes.
  KVP-001 through KVP-005 use dedicated typed record, derive, and verify tasks registered by the
  receipt progression owner; keep inputs and task names derived from the canonical program instead
  of parsing shell command strings. KVP-002 through KVP-004 execute fixed included-build filters as
  argument vectors and own generated proof reports under `build/reports/delivery`.
  Register guarded RED, GREEN, and completion placeholders for every later task so KVP-006 can
  prove all 129 program gates have one Gradle receipt task without manufacturing later receipts.
- Feed PR 633 bytecode and ABI verifiers complete `main` source-set class outputs so Java and
  Kotlin implementations are governed by the same gate.
