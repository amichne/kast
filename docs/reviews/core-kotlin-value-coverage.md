# Core Kotlin value-flow acceptance

This change implements two bounded capabilities: receiver implementation dispatch and local positional `val` destructuring. Native acceptance, affected regressions, and the full semantic coverage gate pass. Documentation guards pass; the separate v2 writing assessment remains unmet. The remaining observations below are separate obligations; this change does not claim universal Kotlin coverage.

## Baseline and evidence

The inspected baseline is `9269df7c457252907ec5153f8f6ef54db9caf466`. The native runner pins IntelliJ Platform build `262.9437.185` in [the version catalog](../../gradle/libs.versions.toml). [The relation build](../../relation/intellij/build.gradle.kts) owns native fixture provisioning and execution.

The supplied screenshots report six result-wrapper scenarios on Kast `0.3721`, public tool contract `17`, Kotlin `2.4.21`, and `KOTLIN_FORWARD_V1`. Those are inherited observations, not executions of this checkout. The original fixture and retained responses were not supplied as executable inputs. New fixtures must establish their own compiler prerequisites and independent expected results; they cannot claim enterprise reproduction parity.

Static inspection identifies these baseline boundaries:

- [Receiver source entry](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeValueSourceReceiver.kt) rejects a declaration without a body before implementation dispatch and admits direct entry only for a final callable.
- [Expression transfer](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueFlowNative.kt) lacks local destructuring, type-test, and cast cases at this baseline.
- [Receiver refinement](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeValueReceiverGuard.kt) rejects conditional field reads. [Branch result handling](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeValueBranchResult.kt) proves an admissible expression result, not path feasibility.

Implementation status and execution evidence are separate. Both selected capabilities are **NATIVE VERIFIED within the accepted scope**: all 27 selected native cases pass. The same tests and grants produce 20 behavior assertion failures on the unchanged baseline. The full semantic coverage gate passes all 186 registered cases, and all 32 selected native regressions pass. Update the evidence record only from actual command results.

## Accepted scope

Receiver implementation dispatch reuses the existing implementation inventory, source ownership, call frames, and coverage qualifications. It must preserve the distinction between the resolved declaration, possible implementation bodies, and a proven runtime target. An exhausted scoped inventory alone does not establish a closed runtime target set.

Local destructuring covers immutable, positional declarations. Its component semantics come from source-owned authored operators or source-owned generated immutable data-class functions. Multiple used entries, such as `val (first, second) = value`, require every used component to prove an immutable stored-property projection. Authored operators support one used entry, including `val (first)` and `val (_, second)`.

Multiple used authored or unproven components keep `UNSUPPORTED_EXPRESSION` with no emitted transfers because their ordered effects are not modeled. Admitted forms preserve initializer evaluation, skipped entries, exact invocation identity, and return provenance. A name such as `component1` is not proof of a field projection. Binary and library component semantics remain outside this slice.

The slice excludes branch refinement, casts, contract consumption, inherited default member-extension bodies, and destructuring in lambdas or loops. Existing supported behavior remains a regression obligation. New failures in those excluded areas do not silently extend this change.

## Semantic families and acceptance

Family IDs remain stable when fixtures, filenames, or diagnostics change. A planned row defines an acceptance obligation; it does not authorize its implementation in this slice.

| ID | Family and status | Independent positive invariant | Negative control and boundary | Required evidence |
| --- | --- | --- | --- | --- |
| KCF-01 | Receiver refinement: **PLANNED; outside slice** | Equivalent `when (this)` and named-subject forms reach the same marked payload uses with the correct receiver and property identities. | Shadowed receivers remain distinct. A conditional receiver is not admitted by deleting its guard. | Indexed native fixture; **NOT RUN**. |
| KCF-02 | Branch compatibility: **PLANNED; outside slice** | A tracked field on a final, disjoint variant never acquires a read inside an incompatible variant arm. | Overlapping types or missing subtype evidence cannot justify pruning. Binding a reference does not prove path feasibility. | Indexed native fixture with exact allowed and forbidden uses; **NOT RUN**. |
| KCF-03 | Local positional destructuring: **NATIVE VERIFIED** | One source-owned authored component, or multiple proven source-owned generated immutable components, reaches the same marked uses as explicit resolved calls. Generated data-class functions preserve the exact fields. | A same-named operator, unrelated return, or unproven projection cannot fabricate a field transfer. `_` invokes no component. Multiple used authored or unproven components emit no transfers and keep `UNSUPPORTED_EXPRESSION`; ordered effects remain outside scope. | **PASS:** 16 focused native cases and three shared composition cases. Scope remains bounded as stated above. |
| KCF-04 | Receiver implementation dispatch: **NATIVE VERIFIED** | Each supported implementation receives the correct receiver formal and returns only to its matched invocation, with source ownership and dispatch evidence. | Empty or incomplete inventories preserve qualifications. An implementation returning a constant cannot return the tracked receiver payload. Source-set exclusions remain effective. | **PASS:** eight focused native cases and three shared composition cases. Scope remains bounded as stated above. |
| KCF-05 | Inherited member-extension bodies: **PLANNED; outside slice** | A supported member extension preserves distinct extension and dispatch receivers through an inherited default body. | A different scope receiver or override is not replaced by the default body. Declaration resolution alone does not select a runtime implementation. | Indexed native fixture with receiver and override controls; **NOT RUN**. |
| KCF-06 | Type tests and casts: **PLANNED; outside slice** | Supported type tests keep their refinement meaning; a successful cast preserves payload identity on its normal edge. | The `Boolean` result is not the payload. A failed unsafe cast cannot acquire a normal-return payload successor. | Indexed native fixture with normal and exceptional controls; **NOT RUN**. |
| KCF-07 | Kotlin contracts: **PLANNED; outside slice** | A consumed contract contributes only the fact it expresses, such as conditional type refinement or callback cardinality. | `callsInPlace` cannot establish payload identity. Contract syntax must not manufacture runtime callback execution. | Compiler contract fixture and independent semantic assertions; **NOT RUN**. |
| KCF-08 | Lambda and loop destructuring: **PLANNED; outside slice** | Supported forms preserve parameter or iteration ownership and resolved component behavior. | Local declaration support cannot be reported as coverage of these forms; unsupported forms keep explicit obligations. | Separate indexed native fixtures; **NOT RUN**. |

## Measure semantic results

Each fixture declares a nonempty set of independent assertions before executing Kast. These fix expected endpoint identities, forbidden paths, and unresolved frontiers. A negative control can require an exact empty endpoint set. Source markers locate the intended uses; K2 independently confirms their declarations, receiver slots, and types. Zero compiler errors are a prerequisite, not proof that the analysis succeeds.

Record these outcomes per case:

- Expected endpoints reached: count and identities, with the expected total.
- Forbidden paths observed: count and the exact conflicting identities; acceptance requires zero.
- Typed frontiers: expected versus observed cause, site, invocation, and retained qualification.
- Fixture prerequisites and execution: pass, fail, skipped, or not run, with the native test count.

For equivalent forms, compare semantic endpoint sets and obligations while preserving each form's raw ranges and provenance. Do not use sink multiplicity, `knownMinimum`, intermediate row count, or presentation pages as coverage. A missing expected endpoint fails even if a deeper intermediate node was reached. A case without independent assertions, or one that did not execute, cannot pass.

## Validation sequence

First run a native fixture that exposes each selected baseline boundary. Then add the corresponding positive path and negative control. Run the smallest selector after each change. Pure constructor or detached-state tests can prove their own invariants, but cannot substitute for compiler resolution, generated-accessor behavior, or indexed implementation discovery.

Use the existing fixtures under `relation/intellij/src/nativeTest/kotlin/io/github/amichne/kast/relation/intellij/`:

| Test path or fixture | Purpose |
| --- | --- |
| `NativeReferenceFixtureTest.kt`, `NativeStoredFieldDispatchTest.kt` | Small indexed source cases and field-dispatch controls. Lambda cases need the pinned standard library installed in their private module. |
| `NativePacketValueFixture.kt` | Existing source preparation, compiler diagnostics, exact markers, selectors, and frozen source verification for composed paths. |
| `NativePacketSourcePolicyTest.kt` | Production-only empty inventory and exact implementation entry under combined source policy. |
| `NativePacketMatchedReturnTest.kt` | Exact invocation returns, distinct same-typed fields, and discarded-input controls. |
| `NativePacketFieldStorageTest.kt` | Constructor and property storage preserve exact field identity. |
| `NativeTransparentWrapperReturnTest.kt` | Prevent an open method or constant override from establishing transparent return. |
| `NativePlainDeferredBoundaryTest.kt` | Records the currently unsupported conditional receiver and unrelated nested-execution boundaries. |
| `NativeReviewedCallbackReturnTest.kt` | Preserves existing callback return rules and their qualifications. |
| `NativeDestructuringCompilerTest.kt` | Checks compiler-selected authored component identities and generated component origins. |
| `NativeDestructuringFlowTest.kt` | Checks exact component flow, explicit-call equivalence, skipped operators, and unsupported component-order controls. |
| `NativeDestructuringRevalidationTest.kt` | Checks exact entry revalidation for generated and authored results and bounds sibling proof work. |
| `NativeReceiverImplementationDispatchTest.kt` | Checks receiver implementation entry, matched returns, receiver slots, source policy, and unsupported default bodies. |
| `NativeCoreValueCompositionTest.kt` | Traverses dispatch and two generated immutable components; checks selected payload, unrelated same-typed field, and constant-return controls. |

After the narrow cases pass, run one composed fixture containing only the selected mechanisms. Its payload reaches an abstract receiver call's supported implementation and returns to that invocation. It then crosses a local positional destructuring declaration and reaches a marked consumer use. Include an unrelated field and a constant-return implementation as negative controls. This fixture must not require deferred branch, contract, or lambda coverage. Use the existing compiler adapter and native runner; no new harness is required.

Start with the affected native method. Then run the selected classes:

```shell
./gradlew :relation:intellij:nativeFixtureTest \
  --tests 'io.github.amichne.kast.relation.intellij.NativeDestructuringCompilerTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeDestructuringFlowTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeDestructuringRevalidationTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeReceiverImplementationDispatchTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeCoreValueCompositionTest'
```

These five classes executed successfully in the recorded current run. The exact command and results appear below. The narrow selectors support the edit loop. `scopedCheck` runs module checks and ordinary unit tests; it does not replace `nativeFixtureTest`.

The existing `semanticCoverageFixtureTest` now includes these five classes. The [case manifest](../../config/kotlin-semantic-coverage/cases.tsv) records their exact cases alongside the existing population. The full `verifyKotlinSemanticCoverage` gate checks the registered population and successful assertions across its native runners. Registration does not establish execution evidence, and a narrow manual run does not satisfy this full gate.

After native acceptance, run affected module checks and required guards:

```shell
./gradlew :relation:contract:scopedCheck :relation:intellij:scopedCheck \
  verifyKastArchitecture verifyJsonContracts --continue
```

Use the checked-in Java toolchain and pinned native distribution. Provisioning failure, compilation failure, skipped native tests, or zero selected tests leaves the corresponding semantic claim unverified.

## Completion and stop rule

KCF-03 and KCF-04 must pass their admitted positive and negative cases. Their composed path must reach every required endpoint. Relevant regressions and required checks must pass before this slice is complete. A successful build, changed rejection message, or deeper frontier alone does not satisfy completion. Other families remain explicitly incomplete.

Classify each subsequent report before changing scope:

1. **Regression in an existing family:** minimize it against the admitted invariant, add the counterexample, and repair that owner.
2. **New supported language primitive:** define its finite scope, independent oracle, controls, and cost before a separate explicit scope decision.
3. **Intentional unsupported case:** keep an accurate typed frontier and the useful evidence already established. Any promotion into supported scope requires a separate explicit decision.

Do not widen the committed scope while fixing a fixture. The stop point is the accepted subset on the pinned language and platform inputs. Passing that subset establishes its stated behavior; it does not establish universal Kotlin coverage or exact feasibility of arbitrary programs.

## Execution evidence

| Item | Recorded status |
| --- | --- |
| Baseline source inspection | Inspected at `9269df7c457252907ec5153f8f6ef54db9caf466`; static evidence only. |
| Final comparable native baseline | 27 tests: seven pass and 20 fail behavior assertions; zero errors or skips. Production sources remain at the baseline; final test sources, fixture inputs, and grants match the current run. |
| KCF-03 native acceptance | PASS: compiler characterization 2/2, component flow 12/12, exact revalidation and bounded sibling proof 2/2. |
| KCF-04 native acceptance | PASS: receiver implementation dispatch 8/8, including matched returns, receiver-slot and source-policy controls. |
| Selected composed path | PASS: 3/3. Selected and second payloads reach their own marked uses; the constant implementation reaches neither output from its stored input. |
| Selected native total | PASS: 27 tests, zero failures, errors, or skips. Captured XML: `../coverage-evidence/current-final-native/`. |
| Affected native regressions | PASS: 32 tests in seven selected existing classes; zero failures, errors, or skips. |
| Full semantic coverage gate | PASS: `verifyKotlinSemanticCoverage`, all 186 registered cases. Native totals: 97 relation, 82 hosted, seven symbol; zero failures, errors, or skips. `EXECUTION_POPULATION` reports no issues. |
| Affected module checks | PASS: `:relation:contract:scopedCheck` and `:relation:intellij:scopedCheck`. Ordinary tests: 250 contract plus 259 IntelliJ, with zero failures, errors, or skips. |
| Architecture and JSON guards | PASS: `verifyKastArchitecture` and `verifyJsonContracts`; JSON syntax report contains 628 fingerprints and zero violations. |
| Knowledge guards | PASS: seven validation tests; 65 concepts and zero citation issues. The two affected concepts were reviewed separately. |
| Tested source identity | Canonical staged source patch: SHA-256 `bfdff4e5e9030c355faa86314e9cd4bbf8992a0ca3c261db74b291a2a309fb32`. |
| Documentation guards | PASS: repository glossary guard under its configured scope; this historical review is `OUT_OF_SCOPE`. `verifyScopedProse` checked this file. |
| Prose checks | Local Vale: zero errors and one grade advisory. Locked assessment: v1 `INCOMPLETE`; the required v2 assessment remains unmet. |

For each execution, record the tested revision or working-tree diff identity, exact command, toolchain and platform identity, and fixture inputs. Add expected and observed endpoints/frontiers, test counts, failures/skips, and the report path. Distinguish newly executed tests from up-to-date reports. A blocked check records its precise unmet claim; it does not prevent unrelated work or become a pass.

The tested source patch is `build/reports/core-kotlin-coverage/tested-source.patch`, with 198,050 bytes. Its canonical command is `git diff --cached --binary --no-renames HEAD -- config/kotlin-semantic-coverage relation`, against baseline `9269df7c457252907ec5153f8f6ef54db9caf466`. Its SHA-256 is `bfdff4e5e9030c355faa86314e9cd4bbf8992a0ca3c261db74b291a2a309fb32`. The patch includes implementation, tests, fixtures, and case registration. It excludes prose so this evidence record does not change the source fingerprint it records.

### Native grants and inputs

Gradle uses Kotlin `2.4.21`. Native fixtures use the Kotlin plugin from `ideaIC:262.9437.185` for the Analysis API and FIR. That plugin's compiler resource reports `2.4.20-ij262-34`, as recorded in the [compiler inventory](../../config/kotlin-semantic-coverage/README.md). The recorded executor uses GraalVM Community JDK `25.0.2+10.1` and Gradle `9.8.1`.

Each ordinary fixture read allows 16 results, 100,000 work units, 60,000 milliseconds, and 1,000,000 detached bytes. The sibling-revalidation negative control deliberately reduces work to three units and requires `WORK_LIMIT_REACHED`.

Receiver and composition flow reads explicitly allow 16,000,000 detached bytes for retained implementation inventory and ownership evidence. Their other limits remain unchanged; composition keeps a maximum of 256 distinct sites. Producer seeding still uses the ordinary fixture grant. These local limits follow the existing implementation-dispatch fixtures; they do not alter the shared fixture or production limits. An earlier current run hit byte limits in six cases before this correction. Endpoint and obligation assertions were not weakened.

The final baseline comparison uses the same five test classes and four frozen fixture files as the successful current run. The [case manifest](../../config/kotlin-semantic-coverage/cases.tsv) records each case's inputs, source hash, oracle, scope, and permitted effects. Source hashes also remain in [the frozen fixture manifest](../../relation/intellij/testData/request-response/frozen-sources.sha256).

| Native class | Baseline passed / failed | Current passed / failed |
| --- | ---: | ---: |
| `NativeDestructuringCompilerTest` | 2 / 0 | 2 / 0 |
| `NativeDestructuringFlowTest` | 3 / 9 | 12 / 0 |
| `NativeDestructuringRevalidationTest` | 0 / 2 | 2 / 0 |
| `NativeReceiverImplementationDispatchTest` | 2 / 6 | 8 / 0 |
| `NativeCoreValueCompositionTest` | 0 / 3 | 3 / 0 |
| Total | 7 / 20 | 27 / 0 |

Both runs have zero errors and skips. Baseline failures occur after frozen-input and zero-error compiler prerequisites. They include missing transfers and precise frontier evidence; they are not 20 independent flow defects. Baseline composition stops at `EXTERNAL_CALL`, before endpoint assertions. Baseline revalidation stops at the missing generated-field transfer, before its revalidation assertions. The current run reaches and passes those assertions.

### Executed current command

The successful selected native run and IntelliJ module checks are recorded in `../coverage-native-current-3.log`. The workspace-local command was:

```shell
KAST_COVERAGE_TASK_ROOT=/workspace/scratch/f10711f3b4bb
KAST_COVERAGE_JAVA="$KAST_COVERAGE_TASK_ROOT/toolchains/graalvm-community-openjdk-25.0.2+10.1"
cd "$KAST_COVERAGE_TASK_ROOT/kast"
env PATH="$KAST_COVERAGE_JAVA/bin:$KAST_COVERAGE_TASK_ROOT/toolchains/vale:$PATH" \
  JAVA_HOME="$KAST_COVERAGE_JAVA" GRAALVM_HOME="$KAST_COVERAGE_JAVA" \
  JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  GRADLE_USER_HOME=/tmp/kast-coverage-gradle-cache \
  UV_CACHE_DIR="$KAST_COVERAGE_TASK_ROOT/uv-cache" \
  ./gradlew --no-daemon --no-watch-fs --no-configuration-cache \
  :relation:intellij:spotlessApply :relation:intellij:nativeFixtureTest \
  --tests 'io.github.amichne.kast.relation.intellij.NativeDestructuringCompilerTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeDestructuringFlowTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeDestructuringRevalidationTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeReceiverImplementationDispatchTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeCoreValueCompositionTest' \
  :relation:intellij:scopedCheck \
  -PnativeFixtureIdeaHome="$KAST_COVERAGE_TASK_ROOT/kast/relation/intellij/build/native-test/idea" \
  --continue --console=plain --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g
```

The contract module checks and knowledge validation passed in `../coverage-native-current-2.log`. Architecture and JSON checks passed in `../coverage-contract-guards-1.log`; the JSON report is `build/reports/json-contracts/report.json`. Those earlier invocations also contained failures in other tasks, so their individual passing checks do not imply a successful aggregate invocation. The current command above completed with `BUILD SUCCESSFUL`. Ordinary test XML remains under each affected module's `build/test-results/test/` directory.

The final baseline command uses the same environment, JVM options, cache, pinned fixture home, and five selectors from the baseline checkout. It omits `:relation:intellij:spotlessApply`, `:relation:intellij:scopedCheck`, and `--continue`. Its log is `../coverage-native-baseline-final.log`; XML is under `../kast-baseline/relation/intellij/build/test-results/nativeFixtureTest/`. The baseline production diff is empty, and all five test files match the current bytes.

The full gate ran `verifyKotlinSemanticCoverage` with the same environment and Gradle/JVM flags, `--max-workers=3`, and no test selector filter. It completed with `BUILD SUCCESSFUL` in `../coverage-required-semantic-gate.log`. Its exact population result is `build/reports/core-kotlin-coverage/semantic-execution.json`: 186 `PASS` results and an empty issue list. Native XML is under each participating module's `build/test-results/semanticCoverageFixtureTest/` directory.

### Affected native regressions

The final regression invocation selected these seven existing classes. All 32 cases passed, with zero failures, errors, or skips.

| Native class | Executed cases |
| --- | ---: |
| `NativeStoredFieldDispatchTest` | 3 |
| `NativePacketSourcePolicyTest` | 2 |
| `NativePacketMatchedReturnTest` | 3 |
| `NativeTransparentWrapperReturnTest` | 8 |
| `NativePacketFieldStorageTest` | 4 |
| `NativePlainDeferredBoundaryTest` | 3 |
| `NativeReviewedCallbackReturnTest` | 9 |

The workspace variables below are the same as in the current command above. This invocation completed with `BUILD SUCCESSFUL` in `../coverage-native-regressions.log`:

```shell
env PATH="$KAST_COVERAGE_JAVA/bin:$KAST_COVERAGE_TASK_ROOT/toolchains/vale:$PATH" \
  JAVA_HOME="$KAST_COVERAGE_JAVA" GRAALVM_HOME="$KAST_COVERAGE_JAVA" \
  JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  GRADLE_USER_HOME=/tmp/kast-coverage-gradle-cache \
  UV_CACHE_DIR="$KAST_COVERAGE_TASK_ROOT/uv-cache" \
  ./gradlew --no-daemon --no-watch-fs --no-configuration-cache \
  :relation:intellij:nativeFixtureTest \
  --tests 'io.github.amichne.kast.relation.intellij.NativeStoredFieldDispatchTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativePacketSourcePolicyTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativePacketMatchedReturnTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeTransparentWrapperReturnTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativePacketFieldStorageTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativePlainDeferredBoundaryTest' \
  --tests 'io.github.amichne.kast.relation.intellij.NativeReviewedCallbackReturnTest' \
  -PnativeFixtureIdeaHome="$KAST_COVERAGE_TASK_ROOT/kast/relation/intellij/build/native-test/idea" \
  --console=plain --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g
```

The seven selected XML suites have timestamps from `2026-10-26T04:34:20.771Z` through `2026-10-26T04:34:54.801Z`. The invocation's HTML report is `relation/intellij/build/reports/tests/nativeFixtureTest/index.html`. Its XML directory also retains five earlier acceptance suites. Counting the whole directory yields 59 cases, but only the 32 selected regression cases belong to this invocation.

### Knowledge and prose limits

The generated pages `openwiki/modules/semantic-reads.md` and `openwiki/semantics/relation/value-flow-and-models.md` have compatible Claim statements. The local-binding explanation needs to include supported destructuring entries when the generated lifecycle refreshes it. Generated pages and their Claims were left untouched; citation validation alone does not establish current prose accuracy.

The documentation guards passed in `../coverage-documentation-guards-2.log`. The glossary report contains 108 documents and zero findings; it classifies this historical review as `OUT_OF_SCOPE`. Their environment used the same Java and Gradle settings, with pinned Node `24.21.0` added first on `PATH`. They ran `verifyDocumentationGlossary verifyScopedProse` with `-PkastProsePaths=docs/reviews/core-kotlin-value-coverage.md`, `--continue`, and `--max-workers=3`. Reports are `build/reports/validation/verifyDocumentationGlossary.txt` and `build/reports/validation/verifyScopedProse.txt`.

The local writing tools come from Slopsentral revision `ad366f7e843534cecda170d7fc41bfe3a6e85eb3` and Vale `3.24.0`. At that revision, `assess_text.py` emits schema version 1 and returns `INCOMPLETE` without a glossary. The repository procedure specifies version 2 without glossary data. Keep the actual v1 report and this unresolved policy mismatch; do not relabel it as v2 or as a passing required assessment. Prose reports belong under `build/reports/documentation/`; they do not establish native semantic acceptance.
