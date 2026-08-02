---
type: Architecture Decision Record
title: Kast Indexer Architecture Decisions
description: Decisions that keep exact-root startup, evidence, writer ownership, and shutdown deterministic.
tags: [internal, indexer, intellij, architecture, lifecycle]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerRuntime.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/project/ProjectOpener.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectBootstrap.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/lifecycle/SourceIndexWriterLease.kt
---

# Kast Indexer Architecture Decisions

These decisions describe implemented boundaries. The linked flow pages contain
operational detail.

## Admit one exact-root authority

**Decision:** A typed boundary validates canonical root, release
compatibility, process identity, endpoint identity, health, readiness, and
conflict state. Every semantic route consumes the admitted proof.

**Consequence:** Kast reuses only an eligible exact-root indexer. Otherwise it
creates one isolated process or returns a typed blocker.

## Keep workspace state in the Kast data root

**Decision:** The active install receipt owns all resolved paths. Each root has
a keyed data directory for configuration, snapshots, `source-index.db`,
descriptors, sockets, and locks.

**Consequence:** Git worktrees remain isolated, and project-local files cannot
become a second path authority.

## Start only on explicit demand

**Decision:** `kast up` and semantic commands admit or create the exact-root
indexer. Foreground editor events are not lifecycle inputs. Kast does not add
continuous supervision.

**Consequence:** After a crash, the next explicit demand admits or creates a
replacement.

## Use IntelliJ and Gradle models as source authority

**Decision:** The indexer opens the root in its isolated VFS and settles the
Gradle model before semantic readiness. Inventory comes from the imported
project model. Hard-excluded build roots cannot be configured back into scope.

**Consequence:** Source coverage retains compiler and build-model provenance.
A recursive filesystem walk cannot claim equivalent completeness.

## Hold one lifetime writer lease

**Decision:** The indexer acquires the exact-root source-index writer lease
before read-write access and holds it until close. Ownership includes process
start identity, so PID reuse cannot satisfy the lease.

**Consequence:** Concurrent launches cannot create two persistent writers.

## Pin reads and close by ownership

**Decision:** Graph reads bind one generation and reject movement. Shutdown
stops transport admission, drains requests, terminates indexing, closes the
store, and removes endpoints only when ownership still matches.

**Consequence:** Readers do not combine generations, and an old process cannot
unlink a replacement process's endpoint.
