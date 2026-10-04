# Resumable local-binding reads: offline evidence

Validated on 2026-10-04, starting from refreshed `origin/main` at
`241a45fafe2b88dfd1a311b0b9da801448d6555f`, on `fix/resumable-local-reads`.
No IDEA process, IDEA plugin installation, opened IDE workspace or RPC server was used.

## Progress and retained evidence

The shared production `LocalBindingReferenceScan` lives in `relation:contract`.
`IntellijLocalBindingReads` supplies native enumeration, reference addresses and K2
confirmation. The existing query interpreter owns scheduling and continuation.
Public `RUN` and `RESUME` operations remain unchanged.

Resource suspension has its own read variant and terminal, separate from exhausted
input and semantic obligations. `QueryImpactSnapshot` retains confirmed transfers,
obligations, actual per-read receipts and a detached local-binding remainder inside
the existing `PipelineCheckpoint`. The remainder contains consumed reference
addresses and emitted destination identities; it contains no PSI, K2 object or live
iterator. Addresses include element range, reference range and native reference kind.

Each resume enumerates under current authority and skips consumed addresses before
confirmation. The scan counts authority/role admission and new confirmations as work;
replay visits check elapsed time and cancellation. Native replay cost and performance
are unverified. Every receipt retains its actual grant rather than assigning the
accumulated observation a fabricated larger grant. Immutable receipt lists share
storage with the checkpoint and ledger through the existing ownership visitor.

## Independent fixture and regression

[QueryLocalBindingResumeTest](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryLocalBindingResumeTest.kt)
specifies eight reference observations: another binding, an unresolved reference,
destination 60, a second reference to destination 60, nested execution, destination 70,
another binding, and destination 80. Two producer invocations, at 10 and 21, reach
binding 30. The authored oracle contains ten distinct paths: each producer reaches
all three destinations and retains both binding qualifications. Destination reads
retain an unsupported-expression qualification.

The pre-fix focused test failed: the small initial grant followed by resume returned
four qualified paths against the authored ten; the large initial grant matched ten.
The test exercised the extracted existing scan behavior and the real query interpreter
before adding resumption. Provisioning and fixture compilation failures were separate
from this assertion failure.

After the fix, drained queries match the oracle and a work-100 execution for work
grants 4, 5, 6 and 9 crossed with result grants 1, 2 and 20. Direct production scans
drain under all 36 combinations of work grants 3, 4, 6 and 100; result grants 1, 2 and
20; and byte grants 110000, 130000 and 1000000. Confirmations occur exactly once per
input, shared destinations emit once, and rejected-only pages advance. The doubles
provide observations and obey the enumeration visitor; they implement no binding
pagination, resumption, deduplication or completion.

[QueryLocalBindingAdverseTest](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryLocalBindingAdverseTest.kt)
covers authority rejection after a confirmed prefix, epoch movement, cancellation,
insufficient checkpoint retention, too little work, insufficient bytes, and elapsed
suspension. Rejections preserve prior confirmed evidence and actual rejected-read
grants. The existing dense callable-evidence retention test also passes.
`ImpactWitnessPresentationTest` checks the encoded receipt shape and preserves
separate work grants 3 and 4 alongside accumulated work 7.

## Verification commands

Commands ran from `/workspace/kast`. Gradle invocations used
`JAVA_HOME=/workspace/.cloud/kast/jdk-25.0.4+7` and
`GRADLE_USER_HOME=/workspace/.cloud/kast/gradle`. Knowledge-parser invocations also
prefixed `PATH` with `/workspace/.cloud/kast/jdk-25.0.4+7/bin` so subprocesses use Java 25.

The regression selector failed before the behavior fix and passed afterward:

```sh
./gradlew :query:service:test --tests '*QueryLocalBindingResumeTest' --console=plain
```

Focused production and retention checks passed:

```sh
./gradlew :query:service:test --tests '*QueryLocalBinding*' --tests '*QueryImpactStorageAdmissionTest' --console=plain
./gradlew -p build-logic test --tests 'support.architecture.KastCleanSlatePolicyTest' --console=plain
```

This complete check invocation passed, with 543 affected module tests and zero failures
or skips. Module `check` includes formatting, Detekt and file-length guards. The five
architecture policy tests passed separately.

```sh
./gradlew :relation:contract:check :relation:service:check :query:contract:check :query:service:check :query:protocol:check :protocol:contract:check :relation:intellij:spotlessCheck :relation:intellij:checkMainKotlinFileLength :relation:intellij:checkTestKotlinFileLength :relation:intellij:detekt verifyJsonContracts verifyKnowledgeBase knowledgeImpact verifyConfigurationIngress --continue --console=plain
```

After tightening adverse-test effect expectations, `./gradlew :query:service:check
--console=plain` passed again. JSON verification found zero violations; knowledge
verification checked 28 concepts with zero issues; configuration ingress found no
findings. Knowledge impact identifies affected generated concepts; their regeneration
belongs to the repository's scheduled OpenWiki workflow.

The following invocation was attempted and remains blocked at dependency resolution:

```sh
./gradlew :relation:intellij:compileKotlin verifyKastArchitecture --continue --console=plain
git diff --check
```

`git diff --check` passed. Adapter compilation and the full compiled architecture
guard did not run locally: required IntelliJ 262.9437.185 artifacts returned HTTP 403 from
`repo.gradle.org`, `packages.jetbrains.team` and `www.jetbrains.com`. The active cloud
environment still has an empty allowed-host list.

## Pull-request check repair

[CI run 37222531834](https://github.com/amichne/kast/actions/runs/37222531834)
compiled `:relation:intellij:compileKotlin` and passed `verifyKastArchitecture`.
Its Kotlin product job failed only at `:cli:verifyMintlifyCallableReference`:
the checked-in generated OpenAPI reference lacked the new receipt fields,
`RESOURCE_SUSPENDED` terminal and `INVALID_PROGRESS` rejection values.

The exact reference verification reproduced that failure locally. Regeneration
through the repository's task, reference comparison and all six focused projection
tests then passed:

```sh
./gradlew :cli:verifyMintlifyCallableReference --console=plain
./gradlew :cli:generateMintlifyCallableReference :cli:verifyMintlifyCallableReference :cli:test --tests '*MintlifyCallableReferenceTest' --console=plain
```

The first command is the expected pre-regeneration failure; the second is the
successful repair check. The generated diff changes only the two query result-item
schema components. The native adapter and compiled architecture evidence comes
from CI; the local environment's dependency restriction remains unchanged.

`./gradlew verifyJsonContracts verifyKnowledgeBase knowledgeImpact --console=plain`
also passed: zero JSON violations, zero knowledge issues and zero impacted concepts
for the two documentation files. From `docs/public`,
`PUPPETEER_SKIP_DOWNLOAD=true npx --yes mint@4.2.841 validate` could not run the
Mintlify validation runtime because its update requires an internet connection.
The PR's documentation job remains the rendering check for the regenerated file.

## Validated review observations

All four open review observations were checked against production behavior before
changing it. Conservation, rejected-receipt projection and replay timeout regressions
failed against the preceding implementation and passed after their fixes:

- A rejected resume no longer bypasses conservation of the suspended prefix's
  transfers and obligations. The constructor rejects missing branches and missing
  qualifications while accepting the complete authored ledger.
- Rejected-read witnesses encode the actual failed read's grant, work, results and
  bytes, both with and without a confirmed prefix. Successful receipts remain on
  the native observation; projection does not duplicate them in the rejection.
- Inspection confirmed that retention visited `readReceipts.values` twice. The
  second visit charged another incoming reference (64 conservatively scaled bytes)
  even though its contents were already visited. Removing it leaves the existing
  retention visitor, expansion factor and admission owners in place.
- A time grant expiring while replaying consumed input preserves the detached
  remainder. Repeated time suspensions before the first candidate each return
  finitely; a later grant drains the scan without reconfirming consumed input.
  Nonviable work and byte grants still reject through the existing owner.

An additional negative contract test exposed receipt domains with a foreign peer
authority being accepted. The source-basis guard now validates both successful and
rejected receipt domains; the test failed before that guard and passed afterward.

The failing regression invocations were:

```sh
./gradlew :query:contract:test --tests '*QueryImpactLedgerTest' :query:service:test --tests '*QueryLocalBindingAdverseTest' --continue --console=plain
./gradlew :query:protocol:test --tests '*ImpactWitnessPresentationTest' --console=plain
./gradlew :protocol:contract:test --tests '*ImpactPeerNativeWitnessValidationTest' :query:service:test --tests '*QueryLocalBindingAdverseTest' --continue --console=plain
```

The rejected-receipt test was subsequently extracted to
`ImpactRejectedReadReceiptTest`, sharing the existing presentation fixture to keep
the repository's class and function size guards intact. The final focused invocation
passed, including the authored grant matrix and storage-admission cases:

```sh
./gradlew :query:contract:test --tests '*QueryImpactLedgerTest' :query:service:test --tests '*QueryLocalBinding*' --tests '*QueryImpactStorageAdmissionTest' :query:protocol:test --tests '*ImpactWitnessPresentationTest' --tests '*ImpactRejectedReadReceiptTest' :protocol:contract:test --tests '*ImpactPeerNativeWitnessValidationTest' --continue --console=plain
```

The complete affected checks and regenerated-reference verification also passed:

```sh
./gradlew :relation:contract:check :relation:service:check :query:contract:check :query:service:check :query:protocol:check :protocol:contract:check :protocol:wire:check :cli:generateMintlifyCallableReference :cli:verifyMintlifyCallableReference :cli:test --tests '*ImpactWitnessSchemaTest' --tests '*MintlifyCallableReferenceTest' verifyJsonContracts verifyKnowledgeBase knowledgeImpact verifyConfigurationIngress --continue --console=plain
git diff --check
```

JUnit reports record 548 tests in the six relation/query/protocol contract and service
modules, 110 protocol-wire tests and 11 selected CLI schema/reference tests, with zero
failures or skips. JSON verification checked 690 fingerprints with zero violations;
knowledge verification checked 28 concepts with zero issues; configuration ingress
reported no findings. OpenWiki impact identifies five generated concepts for the
scheduled owner. The generated OpenAPI change adds rejected receipts to the two
query result-item components.

This evidence establishes offline progress and detached contract behavior. Native
reference enumeration semantics, PSI/K2 integration and runtime performance remain
unverified. Adapter compilation and the compiled architecture guard are established
by CI and do not qualify native runtime behavior.
