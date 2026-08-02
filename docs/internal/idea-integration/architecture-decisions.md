---
type: Architecture Decision Record
title: Headless IntelliJ Integration Architecture Decisions
description: Decisions that keep exact-root headless startup, evidence, writer ownership, and shutdown deterministic.
tags: [internal, headless, intellij, architecture, decisions, lifecycle]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/headless_authority.rs
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/runtime/HeadlessRuntime.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/project/HeadlessProjectOpener.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/gradle/bootstrap/HeadlessGradleProjectBootstrap.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaBackendRuntime.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/lifecycle/SourceIndexWriterLease.kt
  - path: cli-rs/src/agent/navigation/native_graph/query.rs
---

# Headless IntelliJ Integration Architecture Decisions

These decisions describe implemented boundaries. The linked flow pages contain
operational detail.

## Admit one exact-root headless authority

**Status:** Accepted

**Decision:** A central typed boundary validates canonical root, backend kind,
release compatibility, health, readiness, process identity, endpoint identity,
and conflict state. Every semantic route consumes its admitted proof. Legacy
IDEA intent is migration input or a typed rejection; it is not a candidate.

**Consequence:** A stale foreground descriptor cannot be selected and cannot
cause Kast to stop a healthy headless process.

## Keep workspace state in the global Kast data root

**Status:** Accepted

**Decision:** The active install receipt owns all resolved paths. Each
canonical root has a keyed global data directory for configuration, snapshots,
and `source-index.db`, plus keyed runtime descriptors, sockets, and locks.

**Consequence:** Git worktrees stay isolated and project-local implementation
files cannot become a second path authority.

## Start only on explicit demand

**Status:** Accepted

**Decision:** `kast up` and semantic commands start or reuse the exact-root
headless runtime. Foreground application open, close, focus, indexing, and
project events are not lifecycle inputs. Continuous supervision is not added.

**Consequence:** A user-piloted IDE cannot alter Kast lifecycle. After a crash,
the next explicit demand admits or starts a replacement.

## Use private IntelliJ and Gradle models as source authority

**Status:** Accepted

**Decision:** The isolated host opens the root in its own VFS and settles the
Gradle model before semantic readiness. Inventory comes from the private
IntelliJ project model and imported Gradle ownership. Hard excluded build roots
cannot be configured back into scope.

**Consequence:** Source coverage retains compiler and build-model provenance.
A recursive filesystem walk cannot claim equivalent completeness.

## Hold one lifetime writer lease

**Status:** Accepted

**Decision:** The headless process acquires the exact-root source-index writer
lease before read-write store access and holds it until the process closes.
Ownership evidence includes process start identity so PID reuse cannot satisfy
the lease.

**Consequence:** Concurrent launches cannot create two persistent writers.

## Pin reads and close by ownership

**Status:** Accepted

**Decision:** Graph reads bind one generation and reject movement. Shutdown
stops transport admission, drains requests, terminates indexing, closes the
store, and removes endpoints only when exact file and runtime ownership still
match.

**Consequence:** Readers do not combine generations, and an old process cannot
unlink a replacement process's endpoint.
