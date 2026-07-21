# ADR 0031: Sole transactional setup authority

Status: Accepted

Date: 2026-07-20

## Context

Kast previously combined direct bundle activation, repair, machine
reconciliation, Homebrew state, shell projections, and platform-specific
installers. A successful command could therefore leave independently selected
CLI, backend, plugin, skill, guidance, and config generations.

## Decision

`kast setup --source <bundle>` is the sole persistent installation and
configuration operation on macOS and Linux. It accepts one manifest-bound
bundle containing the CLI, headless backend, IDEA plugin, skill, and guidance.
Every installed artifact, config file, and receipt lives under `KAST_HOME`.

Setup holds one exclusive lock, discards stale staging, stages a complete
release, verifies all artifact digests and required paths, atomically switches
`current`, and verifies the active CLI. It archives recognized prior Kast state
before replacement. If final verification fails, setup restores the prior
active release and reports the failed phase and exact rerun command.

The bootstrap installer, development refresh, hosted-agent checks, and release
verification delegate to this operation. Repair, machine activation,
Homebrew publication, shell installation, direct release activation, and the
separate Linux installer are removed instead of retained as aliases.

This record supersedes the installation and configuration authority decisions
in ADRs 0013, 0023, 0027, 0028 (unsigned GitHub IDEA distribution), 0029, and
0030. Their exact-workspace IDEA runtime and semantic lease decisions remain in
force where they do not create a second installation authority.

## Consequences

A successful setup has one active release and one receipt. Repeated and
concurrent invocations converge without partial state. A failed invocation
leaves either the old verified release or the new verified release active.
Package managers and IDE profiles are not authorities for Kast installation.

## Validation

`.github/scripts/test-setup-contract.sh` proves the transaction, rollback,
idempotence, serialization, stale-staging cleanup, digest validation, retired
surfaces, and workflow delegation. The IntelliJ receipt tests prove that the
plugin resolves only the active setup receipt.
