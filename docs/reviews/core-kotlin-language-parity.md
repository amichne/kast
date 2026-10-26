# Core Kotlin language parity

All eight KCF families now have separate acceptance cases.
The original KCF-03 and KCF-04 cases remain unchanged.
The six remaining families add 51 cases with independent source markers, exact identities, and negative controls.
The [case manifest](../../config/kotlin-semantic-coverage/cases.tsv) records each assertion, fixture digest, scope, and grant.
The manifest preserves the original 186 rows as an unchanged prefix.

This report extends the [original acceptance report](core-kotlin-value-coverage.md).
The baseline for these additions is `221087638323fbc8347ffcf1760b4333fd277348`, the merge of [PR #1098](https://github.com/amichne/kast/pull/1098).
Its tree equals the tested candidate `27308f3647fda1e788cb4a5637f959ac364da32e`.
The native runner uses the pinned IntelliJ Platform and Kotlin plugin from [the version catalog](../../gradle/libs.versions.toml).
These fixtures establish language-level behavior without factory-name or wrapper-type rules.

## Accepted behavior

| Family | Executable scope | Negative control |
| --- | --- | --- |
| KCF-01 | Implicit, named, and immutable-alias subjects preserve the exact receiver and field. | A shadowed dispatch receiver cannot replace the extension receiver. |
| KCF-02 | Ordered `is`, `!is`, and `else` arms use a tracked field's final owner. | Open owners and unproved concrete type arguments keep explicit obligations. |
| KCF-03 | Original local positional destructuring cases and shared composition cases remain unchanged. | Original mutable, ordinal, skipped-component, and ordered-effect controls remain active. |
| KCF-04 | Original receiver implementation cases and shared composition cases remain unchanged. | Original receiver-slot, source-policy, constant-result, and inventory controls remain active. |
| KCF-05 | Final dispatch types select inherited bodies for receiver and ordinary argument flow. | Open dispatch keeps its obligation. An override or another receiver cannot substitute for the selected body. |
| KCF-06 | Successful casts preserve payload identity. Type-test results remain separate `Boolean` values. | Failed unsafe casts keep abrupt completion, including selected, nested, parenthesized, and `Nothing` operands. |
| KCF-07 | Compiler-admitted contract descriptions remain separate from real callback invocations and payload returns. | Equal `callsInPlace` contracts do not equate a payload return with a constant return. |
| KCF-08 | Authored and generated component outputs preserve exact lambda or loop entries and their uses. | Dormant callbacks and erased callback owners reject. Multiple used authored components keep an ordered-effect obligation. |

KCF-01 and KCF-02 share 16 cases in `NativeReceiverBranchParityTest`.
KCF-05 has 10 cases, KCF-06 has 15, KCF-07 has three, and KCF-08 has seven.
The existing 20 cases in `NativeDeclaredEffectCallbackTest` also remain active.
They keep declared callback effects separate from fabricated runtime invocations.

## Proof and limits

Each native fixture first needs an indexed project with no compiler errors.
Expected endpoints come from authored declarations and source markers.
Assertions compare exact roles, owners, fields, call frames, forbidden destinations, and finite failure causes.
Compiler success alone does not satisfy these assertions.

Behavioral RED exposed missing receiver refinement, inherited-body admission, contract-description handling, component forms, and cast completion.
Later cast cases exposed selected-result bypasses and erased outer type arguments.
Provisioning, compilation, and lint failures did not count as behavioral RED.
An existing structural unit case now admits lambda and loop shapes before semantic admission.
Native cases separately prove callback ownership and rejection.

Branch coverage admits simple type-test arms with final stored-field evidence.
It does not establish arbitrary path feasibility or predicate evaluation.
A cast with an unproved target or erased generic arguments keeps `UNSUPPORTED_EXPRESSION`.
A failed safe cast stops operand payload flow, but its selected null result remains a distinct value.
Contract coverage includes callback cardinality and real source-return dependencies.
It does not claim arbitrary conditional-contract refinement.

Lambda and loop coverage starts at compiler-proven component outputs.
It does not claim collection-input provenance, iterator payload flow, or arbitrary callback-argument flow.
Multiple used authored components keep explicit obligations because this change does not model their ordered effects.
The generated-field cases compare independently selected constructor fields with compiler-selected components.

IntelliJ imported this exact worktree, and Kast supplied complete source queries and diagnostics during development.
These reads used the installed host.
The new behavior has native-fixture evidence, without an installation or release claim.

## Executed checks

- **Passed:** 130 native acceptance and regression cases, with zero failures, errors, or skips after the helper refactor.
- **Passed:** `:relation:contract:scopedCheck` and `:relation:intellij:scopedCheck` after the helper refactor.
- **Passed:** `knowledgeImpact`, `verifyKnowledgeBase`, and `verifyJsonContracts`.
- **Passed:** `verifyDocumentationGlossary` and `verifyScopedProse`.
- **Passed:** `verifyKotlinSemanticCoverage`, with 237 `PASS` results and no population issues.
- **Passed:** the full product gate, with 595 tasks in 8 minutes 20 seconds.
- **ASSESSED:** the pinned writing procedure. The grade-8 advisory remains because exact semantic terms preserve the acceptance scope.

The prose report uses schema version 1 and Vale 3.24.0.
Its terminology result is `OUT_OF_SCOPE` under the glossary policy for historical reviews.
Reports reside under `build/reports/documentation/core-kotlin-language-parity-*`.
An assessment does not establish reader comprehension.

The focused native selection contains the five new classes, the five original acceptance classes, seven existing regression classes, and `NativeDeclaredEffectCallbackTest`.
Its XML resides in `relation/intellij/build/test-results/nativeFixtureTest/`.
The [relation build](../../relation/intellij/build.gradle.kts) also registers all five new classes in `semanticCoverageFixtureTest`.
The full execution manifest contains 237 distinct case identities.
Registration alone does not prove their execution.

The exact local commands use the repository's pinned tools:

```sh
mise exec -- ./gradlew :relation:contract:scopedCheck :relation:intellij:scopedCheck \
  knowledgeImpact verifyKnowledgeBase verifyJsonContracts
mise exec -- python3 distribution/release/run_product_gate.py
mise exec -- ./gradlew verifyDocumentationGlossary verifyScopedProse \
  -PkastProsePaths=docs/reviews/core-kotlin-language-parity.md
```

`knowledgeImpact` identifies two generated concepts: `openwiki/modules/semantic-reads.md` and `openwiki/semantics/relation/value-flow-and-models.md`.
Their ownership and evidence distinctions remain valid.
The generated explanation of local bindings needs the new component forms at its next scheduled refresh.
This change leaves generated pages under their existing lifecycle.
Citation checks do not prove current prose accuracy.
