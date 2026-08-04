# Event-driven semantic atomicity

Kast treats a workspace change as an invalidation signal. An event does not
describe semantic truth. Reconciliation and verification establish semantic
truth.

## Lifecycle

```text
READY -> DIRTY -> SETTLING -> REFRESHING -> RECONCILING -> VERIFYING -> READY
                                      \-> BLOCKED <-/
```

Each relevant VFS event withdraws semantic admission and adds one typed signal
to a bounded set. The worker waits for 250 ms of quiescence. It then drains the
set once. Ten thousand source events therefore request one source pass, not ten
thousand tasks.

The transition phases have these responsibilities:

1. **DIRTY** blocks new semantic reads and mutations.
2. **SETTLING** conflates an event burst. It does not refresh or reconcile.
3. **REFRESHING** refreshes the VFS. A build-semantic signal also requests a
   Gradle refresh and waits for the imported model to settle.
4. **RECONCILING** is the only phase that writes candidate semantic evidence.
5. **VERIFYING** computes the workspace-state identity (WSID) again. A changed
   WSID or a new event discards the pass.
6. **READY** opens semantic admission only after immutable SQLite publication
   and atomic pointer replacement complete.
7. **BLOCKED** records a typed phase and reason. It keeps the previous published
   generation unchanged.

A five-minute recovery audit runs after a stable pass. It repeats WSID capture
and reconciliation, so an event missed by the VFS cannot cause permanent stale
readiness.

## Signal boundary

The exact-root VFS listener classifies source, build-semantic, configuration,
scope, and Git-worktree paths. A signal only wakes the transition worker. A Git
hook or commit identifier is not an authority because it cannot represent dirty
or untracked inputs and is not present for every filesystem mutation.

VFS refresh occurs before each pass. A build-semantic signal refreshes the
linked Gradle project and waits for module and source-set import settlement. A
refresh failure closes admission and produces BLOCKED behavior. Kast does not
use a foreground IDE project as a repair mechanism.

## Workspace-state identity

WSID is a SHA-256 digest of all admitted semantic inputs:

- eligible Kotlin and Java paths and content, including admitted generated and
  untracked files;
- Gradle scripts, settings, properties, wrapper inputs, and version catalogs;
- the effective indexing scope;
- module names, source roots, SDK identity, order-entry validity, class roots,
  and the classpath fingerprint.

The `.git` directory and commit identity are excluded. Two identical semantic
workspaces at different commits have the same WSID. A dirty or untracked
admitted source changes the WSID.

## Publication and reads

The live writer database is a candidate while admission is closed. After a
stable reconciliation, Kast holds the writer lock and uses `VACUUM INTO` to
create a consistent database image. It verifies that the source-index
generation did not move and that no incomplete module or pending update exists.

Publication uses this order:

1. write the candidate database;
2. sync it;
3. atomically move it to a unique immutable generation file;
4. sync the generation directory;
5. write and sync a temporary manifest;
6. atomically replace `current.json`;
7. open READY admission for the same reconciliation revision.

The pointer is the visibility boundary. An interruption before pointer
replacement can leave an unreferenced immutable candidate, but it cannot change
the current generation. The prior generation is not deleted.

Every external semantic read and mutation opens a revision token while READY.
The backend checks the token again before it returns. A request that starts
outside READY or spans an invalidation fails with a conflict. The internal
reconciliation writer has a separate narrow entry point and cannot be reached
through the external protocol.

## Executable guarantees

Focused tests cover event invalidation, a 10,000-event storm, moving-pass
rejection, build refresh failure, Git-independent identity, dirty and untracked
identity, read-token invalidation, pointer-last crash recovery, and publication
before READY. The full module suites remain the delivery gate.

The [research ledger](research-ledger.md) maps these decisions to repository
source, platform contracts, and tests.
