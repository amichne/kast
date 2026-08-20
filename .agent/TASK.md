# Task Contract

## Goal

Pull request #625's installed-product acceptance test consumes the structured symbol discovery and description CLI documents and passes.

## Allowed Writes

- `.agent/TASK.md`
- `packaging/test-installed-product.sh`

No other paths may be modified.

## Allowed Reads

- `.agent/TASK.md`
- `AGENTS.md`
- `cli/AGENTS.md`
- `build.gradle.kts`
- `packaging/test-installed-product.sh`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalCliOutcomeProjectors.kt`
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt`
- Pull request #625 metadata, checks, and GitHub Actions logs.

## Non-Goals

- Changing the structured Kotlin protocol or CLI projection.
- Updating release or enterprise acceptance consumers that are not part of the failing check.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
./gradlew installedProductTest
```

Expected failure:

The installed-product parser raises `KeyError: 'candidateSelectors'` because it reads the retired flat discovery field instead of the structured `items` document.

## Green Proof

Command:

```shell
./gradlew installedProductTest && python3 .github/scripts/check-repository-shape.py --root .
```

## Done When

- The installed-product acceptance test reads a declaration candidate from `items` and the described symbol from `symbol`.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Live CI RED: run `32333294669`, job `96317923710`, failed in `:installedProductTest` with `KeyError: 'candidateSelectors'`.
- Local RED: `./gradlew installedProductTest` failed in 1m55s with the same `KeyError`.
- Implementation: the installed-product parser now consumes declaration items and structured symbol documents.
- Local GREEN: `./gradlew installedProductTest && python3 .github/scripts/check-repository-shape.py --root .` passed; repository shape reported zero violations.

## Out-of-Scope Findings

- None
