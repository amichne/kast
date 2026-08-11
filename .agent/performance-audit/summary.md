# Kast performance increment evidence

Date: 2026-08-11
Workspace: `/Users/amichne/code/kast`
Candidate parent: `4fe7e33ca` (`0.23.0-4-g4fe7e33ca`)
Installed release digest: `36a6d49a420f9fec6dcc723736f99eea0d833a59a6f57a15c5a088ee62a3297e`
Installed manifest digest: `c77e7051720925cec53501df94e9b590e405512910da8e4864e44aca00788582`

## Increment

This increment removes two material sources of duplicate IntelliJ work:

- process-wide synchronous VFS refresh is replaced by watcher-backed recursive
  refresh over a typed minimal set of workspace, Gradle, configuration,
  configured compiler-source, compiler-artifact, and classpath authorities;
- initial reconciliation reuses the compiler-ready Gradle model already
  established by `GradleProjectBootstrap` instead of unconditionally importing
  it again.

The imported model is represented by a private capability constructed only by
the Ready bootstrap transition. One build-semantic identity projection is
sampled before and after bootstrap. Changed input, a skipped bootstrap, or later
identity drift fails closed to the existing VFS plus Gradle refresh path.
Initial reuse still performs the bounded watcher-backed VFS refresh before
workspace identity capture.

## Comparable runtime evidence

Before this increment, exact-process traces showed Kast-triggered global VFS
refresh feedback and repeated 17.5-22.9 second Gradle imports. IntelliJ scanned
roughly 110,600 and 213,300 files per cycle while finding no files to index.

The final development install reached READY with PID `69887`. Its exact startup
trace contained one IntelliJ Gradle resolution:

```text
13:43:08 External project resolution started
13:43:40 External project resolution executed in 31,794 ms
13:43:56 Project opened: kast
13:44:06 InitialProjectModel reconciliation started
13:46:18 Kast IDEA index completed
```

There is no second `ExternalSystemUtil` resolution between project open and
READY. The full install command, including local rebuild and repository-database
work, completed in 335.18 seconds; this is not claimed as a cold-start latency
improvement. The publishable result is removal of the measured duplicate import
and process-wide refresh, with remaining cold indexing cost left visible.

Three comparable warm semantic searches succeeded earlier in the same audit at
0.73 s, 0.64 s, and 1.03 s (median 0.73 s), each returning the same
`WorkspaceTransitionRuntime` match and semantic generation.

## Resource audit

- Warm idle five-second IntelliJ MCP sample: 26.72 ms process CPU, 0.534% of
  one CPU; no Kast-owned hot or lock-owning thread.
- JVM heap: about 1.41 GiB used of 2 GiB committed. Dominant objects were
  IntelliJ VFS/workspace/index structures; Kast-owned workspace identity and
  inventory objects were individually small.
- Exact workspace source database: 88 MiB with no active WAL/SHM after stop.
- Accumulated test workspace state is about 1.9 GiB, but it is historical local
  test data rather than a live runtime bottleneck and is not changed here.

## Proof

- Focused startup/authority/VFS suite: pass in 27 seconds.
- Full `:indexer:verifyPortableDistLayout :indexer:test --rerun-tasks`: pass.
- Distribution ownership gate scans jar contents and preserves IntelliJ Kotlin
  class ownership.
- Independent Kotlin transition review: clean; no actionable findings.
- Exact development install: READY with no classloader failure and one startup
  Gradle resolution.

The five-minute idle lease remains unchanged by explicit user direction.
