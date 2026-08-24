# Topology contract module guide

`:topology:contract` owns detached generation identity, admitted Kotlin-file candidates,
compiler-grounded symbols and edges, complete per-file coverage, snapshot manifests, build
outcomes, read eligibility, and narrow extraction and persistence ports.

## Invariants

- A candidate is a Kotlin file below one exact `PublishedWorkspace.sourceRoots` entry and retains
  its Gradle project and source-set owner plus exact content hash. Extraction admission and
  completion pairing compare the entire candidate evidence, not only its path or ordering key.
- Only `CompleteTopologyFile` can enter `CompleteTopologyGeneration`; missing, duplicate, extra,
  cross-generation, or same-node contradictory evidence is rejected as closed data.
- `TopologyNodeIdentity` combines compiler identity, exact file, and declaration range. Compiler
  identity may repeat at distinct locations; duplicate exact nodes and edges whose endpoints do
  not name an admitted exact node are rejected.
- Snapshot eligibility requires exact canonical root, workspace state identity, and evidence
  generation equality. A stale snapshot remains named but cannot acquire read authority.
- Contract types are immutable and detached. Do not import IntelliJ, K2, JDBC, filesystem effects,
  runtime dispatch, or protocol serialization.
- Unbounded traversal, reachability, cycle, SCC, condensation, and quotient APIs are not part of
  the stable contract.

## Verification

1. Run `./gradlew :topology:contract:test`.
2. Run `./gradlew :topology:build:test :evidence:sqlite:test` after lifecycle changes.
3. Run `./gradlew verifyTopologyAuthority verifyKastModuleGraph verifyForbiddenEffects`.
