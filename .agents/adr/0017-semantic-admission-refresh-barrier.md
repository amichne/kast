# ADR 0017: Semantic admission refresh barrier

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 only where that record treats workspace refresh
as a state invalidation request that callers must follow with separate,
potentially incomplete diagnostics. The broader public product, AXI, runtime,
source-index, and audit rules in ADR 0006 remain in force. It extends the
fail-closed per-file diagnostics contract delivered for issue #332.

## Decision

A successful focused workspace refresh is a semantic-admission barrier for
every existing Kotlin path in the request. Returning a path in
`refreshedFiles` proves that the IDEA runtime has discovered the file, assigned
it to source content, admitted it to the IDE index, and successfully opened a
Kotlin analysis session. Immediate diagnostics may therefore reuse the same
path without a caller-managed delay or a second refresh.

Each requested path has one typed admission ledger entry. The ledger preserves
four facts independently:

1. filesystem and VFS discovery;
2. source-module ownership;
3. IDE index admission;
4. Kotlin analysis availability.

The IDEA backend probes the four stages in that order. Already-admitted files
take one synchronous fast-path probe. Pending VFS, index, or PSI admission is
retried at a short fixed interval until a bounded deadline. The final result
reports attempts and elapsed time so a bounded wait is observable. It never
turns a still-pending path into a false refreshed or ready result.

Removed paths are terminal refresh results and appear in `removedFiles`; later
diagnostics for the deleted path still return issue #332's typed
`MISSING_ON_DISK` incomplete-analysis evidence. An existing path that remains
unowned, pending, or unavailable at the deadline returns
`SemanticAnalysisOutcome.INCOMPLETE` plus the corresponding
`FileAnalysisStatus`. Ordinary Kotlin compiler diagnostics do not block
admission because they prove that analysis ran.

Full workspace refresh remains an explicit host refresh request. It does not
claim per-file semantic admission because the request contains no bounded file
set to prove.

## Runtime Ownership

`analysis-api` owns the host-agnostic admission ledger, its state enums, the
invariant-checked `RefreshResult`, and the relationship to issue #332's
`FileAnalysisStatus` and `SemanticAnalysisOutcome` types.

`backend-idea` owns VFS refresh, source-content classification, IDEA index
observation, Kotlin PSI/analysis probing, the fast path, and bounded retry. It
must keep the four observations separate even when several failures map to the
same `PENDING_INDEX` semantic-analysis state.

`analysis-server` owns sequencing Kast mutations through the refresh barrier
before import optimization or diagnostics. Raw external refresh continues to
dispatch through the same backend contract. Other runtime hosts must either
provide equivalent proof or return typed incomplete admission; they may not
copy IDEA-specific logic into the shared contract.

## Public Contract

`raw/workspace-refresh` keeps its method name and targeted/full query shape.
Its additive response evidence includes ordered per-file admission statuses,
the derived semantic outcome, admitted and pending counts, and bounded-wait
progress. `refreshedFiles` contains only admitted existing paths;
`removedFiles` contains confirmed absent paths.

The typed `kast agent diagnostics` flow keeps refresh and diagnostics as
separate steps. A complete focused refresh followed by diagnostics must not
contradict itself by reporting the same admitted file missing or pending.
Persistent incomplete refresh evidence must fail closed through the same typed
semantic-analysis contract and non-zero command behavior established by issue
#332.

## Source Of Truth

| Contract | Source |
| --- | --- |
| Admission and completeness models | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/` |
| IDEA probing and bounded wait | `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/` |
| Kast mutation sequencing | `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt` |
| Raw refresh dispatch | `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt` |
| Generated protocol reference | `cli-rs/protocol/`, generated from Kotlin contract owners |

## Validation

Focused integration coverage must exercise new production and test files,
moved paths, deleted paths, and files created through Kast mutation. It must
also prove a clean focused refresh remains below one second and a permanently
pending path exhausts the bounded retry with typed incomplete evidence.

Run:

```console
./gradlew :analysis-api:test
./gradlew :analysis-server:test
./gradlew :backend-idea:test
./gradlew test
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```

Regenerate protocol artifacts from their owning Gradle tasks whenever the
serialized refresh model changes. Do not hand-edit generated Markdown, YAML,
JSON catalogs, schemas, or examples.

## Change Rule

Future refresh implementations may tune the bounded retry policy only with a
focused latency and persistent-failure proof. Weakening a successful focused
refresh back to VFS acknowledgement, merging the four stage facts, or treating
pending admission as clean requires a superseding ADR.
