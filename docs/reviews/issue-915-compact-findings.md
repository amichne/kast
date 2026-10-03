# Review #915: compact retained findings

This branch adds one compact finding per original retained impact path. Each finding preserves the original path ordinal and row ID, producer, destination, representation alternatives, provenance references, terminal and boundary obligations. Expanding a finding reads the original evidence through the existing result store. It does not schedule semantic execution or change semantic coverage.

## Review base

Compare `codex/representation-impact-foundation` at `93a47e83bbfaca148428b4d4061d42bc7ee8f4b1` with `codex/issue-915-compact-findings`. The production change is the original commit `5c57c6f009bac1db1709a9ff4cd94515d181766a`; the added review note and navigation do not change its code. The prerequisite branch contains the still-unsplit initial implementation and its first repairs. This is a stacked review against that prerequisite, rather than an independent change against main.

[Focused comparison](https://github.com/amichne/kast/compare/codex/representation-impact-foundation...codex/issue-915-compact-findings)

## Invariant and reading order

1. [QueryImpactFinding](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactFinding.kt) admits an ordinal against the original ledger and retains its exact path by reference. Distinct routes to the same destination remain distinct findings.
2. [RetainedQueryPresentation](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt) requires the original ledger and row-ID inventory to agree before selecting a findings window.
3. [ImpactFindingProjection](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingProjection.kt) and its terminal projection produce the compact document; [ImpactWitnessDocuments](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactWitnessDocuments.kt) own the typed wire shape.
4. [ImpactAccountingValidation](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactAccountingValidation.kt) rejects inconsistent section counts, original ordinals, duplicate row references and empty modeled branch sets. Selected presentation retains the original qualified closure.

The change includes encoded goldens, schema consumers, generated public catalogs, examples and source-bound knowledge. It changes no `query/service`, `relation`, `runtime` or `workspace` production files relative to its prerequisite. Native flow and the existing investigation store remain the execution authorities.

## Executed checks

The focused selector executed 20 cases in six classes, with zero failures, errors or skips: three query-contract, six query-protocol, eight public-contract and three CLI schema cases. It proves distinct original routes, stable links and ordinals, full-path expansion without semantic replay, pagination conservation, representation/terminal qualifications, compact encoding and malformed reference rejection. These are compiler, schema and controlled adapter proofs.

```sh
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:contract:test --tests '*QueryImpactFindingTest' \
  :query:protocol:test --tests '*ImpactFindingPresentationTest' --tests '*ImpactFindingQualificationTest' \
  :protocol:contract:test --tests '*ImpactFindingAccountingTest' --tests '*ImpactFindingShapeTest' \
  :cli:test --tests '*ImpactWitnessSchemaTest'
```

The exact selector ran at `5c57c6f009bac1db1709a9ff4cd94515d181766a`; its log is `/tmp/kast-issue-915-compact-focused.log`. The prerequisite passed its mandatory pre-push `productBuildGate`, recorded in `/tmp/kast-impact-foundation-push.log`. Publication of this branch also runs the repository's mandatory gate with GraalVM 25 and a fresh stable-release version observation; its outcome belongs to the final push handoff.

## Scope and evidence limits

This unit covers compact reporting over an original investigation ledger. Later pending-checkpoint retention, independently requested-site accounting and independent-peer admission are separate changes. It does not close #915 or claim semantic completion for qualified paths.

No new live IntelliJ semantic qualification was performed for this separation. Existing installed observations retain their original artifact identities in the [full proof receipt](https://github.com/amichne/kast/blob/2a1dc5681070e610ce8d8c849400b5ae4fe35ed2/docs/reviews/representation-impact-proof.md). A smaller encoded document is not evidence of CPU, heap or latency improvement.
