# Task Contract

## Goal

Kast patch release `v0.25.4` publishes successfully with release acceptance consuming the structured symbol CLI documents.

## Allowed Writes

- `.agent/TASK.md`
- `integration-tests/enterprise_acceptance.py`
- `packaging/verify-published-runtime-delivery.sh`

No other paths may be modified.

## Allowed Reads

- `AGENTS.md`
- `.agent/TASK.md`
- `build.gradle.kts`
- `benchmarks/enterprise-acceptance.json`
- `integration-tests/enterprise_acceptance.py`
- `packaging/verify-published-runtime-delivery.sh`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalCliOutcomeProjectors.kt`
- GitHub Actions release evidence and release metadata.

## Non-Goals

- Changing product behavior or Kotlin source.
- Changing acceptance thresholds.
- Changing release asset formats or destinations.
- Changing secrets, permissions, environments, or deployment targets.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
./gradlew -Pversion=0.25.4 enterpriseAcceptance
```

Expected failure:

Enterprise acceptance rejects a complete structured discovery result because it expects the retired bounded-discovery response shape.

## Green Proof

Command:

```shell
./gradlew -Pversion=0.25.4 enterpriseAcceptance && bash -n packaging/verify-published-runtime-delivery.sh && python3 .github/scripts/check-repository-shape.py --root .
```

## Done When

- Enterprise acceptance reads structured discovery declaration items and accepts complete results within the configured bound.
- Published-runtime verification reads declaration candidates and described symbols from structured CLI documents.
- The Green Proof passes.
- The change reaches `main` with passing CI.
- GitHub release `v0.25.4` is published from the resulting `main` commit.
- The release workflow's fresh published installation check passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Release run `32379780859` passed the repaired release contract and failed in `enterpriseAcceptance` because a complete 12-item structured discovery result did not satisfy the stale bounded-result assertion.
- Release `v0.25.4` and tag `v0.25.4` do not exist.
- Local RED: `./gradlew -Pversion=0.25.4 enterpriseAcceptance` failed in 1m34s with the same stale discovery assertion.
- Implementation: release acceptance now consumes structured discovery, symbol, relation, and traversal documents.
- Investigation: the widened run reached stale-selector proof and exposed a retired exit-code expectation; operation rejections are structured documents with exit code 0, while nonzero codes are reserved for boundary failures.
- Local GREEN: `./gradlew -Pversion=0.25.4 enterpriseAcceptance && bash -n packaging/verify-published-runtime-delivery.sh && python3 .github/scripts/check-repository-shape.py --root .` passed in 1m with zero shape violations.

## Out-of-Scope Findings

- None
