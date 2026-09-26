# Verification signal quality

Reviewed on 2026-09-13 against `25d76f529425465a98a7f24e0ef3a7f58d7fce5a`, after merging dependency PR #725.

Four checks deserve follow-up. Two produce reproducible false alarms; two encode assumptions about names or serialization that are weaker than the contracts they check. This review adds Engineering Dictum 10 in [AGENTS.md](../../AGENTS.md); it does not change these checks.

## Scope and standard

This was a bounded pattern scan of Gradle build logic and CI scripts, followed by selected task wiring, implementation, and test reads. It was not a line-by-line audit or proof that no other cases exist. The question was whether a literal represents an actual contract, or merely a convenient proxy for it.

A useful guard must reject a meaningful violation and accept a relevant change that preserves the invariant. False alarms and missed violations both damage trust. Exact identities and representations remain appropriate where the contract requires them.

## Findings

### 1. Architecture input coverage depends on spelling

The [architecture convention](../../build-logic/src/main/kotlin/kast.architecture.gradle.kts) registers compiled classes only for source sets named `main` or `gradleTooling` (line 125). It separately classifies dependency configurations as production when their names contain neither `test` nor `fixture` (lines 184–186). Exported dependencies come only from the configuration named `api` (line 163).

These rules do not establish whether an input belongs to production. For example, `latestImplementation` contains `test` and is excluded; a production source set with another name contributes no compiled classes. A test-only configuration without either substring can instead contribute production edges.

**Evidence:** the selection predicates establish this sensitivity directly. No existing shipped module was demonstrated to bypass the guard; this is a coverage gap when extending or renaming build inputs.

**Repair:** register source-set and configuration roles explicitly and derive edges from their Gradle relationships. Reject unclassified production inputs. Prove that adding or renaming a production source set retains coverage and that a forbidden dependency in it still fails.

### 2. A harmless filename becomes evidence of embedded runtime code

[VerifyControlDistributionTask](../../build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt) rejects any path containing `kast-indexer`, `idea-home`, `plugins/Kotlin`, or `plugins/gradle` (lines 67–69). This task is wired into [release assembly](../../build.gradle.kts). The substring search applies to ordinary documents as well as runtime payloads.

**Reproduced with the actual Gradle task:** a minimal control distribution with the required files, executable launchers, and archive passed. Adding a documentation-only `share/kast/licenses/kast-indexer-NOTICE.txt` made it fail with “control product contains semantic runtime payload.” Renaming the file to `retired-runtime-NOTICE.txt`, preserving its bytes, passed. A temporary Gradle init script redirected the task's directory/archive inputs and removed its staging dependencies; no production implementation was changed.

**Repair:** define forbidden layout roots or path components precisely and inspect artifact contents. Preserve the task's separate JAR-entry checks for prohibited runtime packages. Cover both an actual embedded runtime artifact and harmless documentation mentioning a retired component.

### 3. Console formatting becomes missing task-graph proof

[routine_gate.py](../../.github/scripts/ci/routine_gate.py) extracts task identities using `^(:\S+) SKIPPED$` (line 33). [Routine CI](../../.github/scripts/ci/verify-checks.py) uses this result to check required and prohibited tasks. Its [unit tests](../../.github/scripts/ci/test_routine_gate.py) synthesize the same console spelling, so they exercise task policy without testing presentation variance.

**Reproduced by calling the actual `inspect` function:** all 22 required task lines produce `complete`. Adding one trailing space to each otherwise identical line produces `rejected` with 22 missing-proof findings. This demonstrates parser sensitivity; it does not claim the current Gradle version emits those spaces or that current CI fails.

**Repair:** obtain a bounded, structured task inventory from Gradle and compare decoded identities. If a console adapter remains, unsupported evidence should produce a distinct evidence-format failure rather than a cascade of alleged missing tasks. Keep the required and prohibited task identities: those express real policy.

### 4. JSON tests couple field meaning to compact rendering

[InstalledKnowledgeProjectionTest](../../build-logic/src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt) checks generated JSON with substrings, including `"declarationPath":"Outcome"`, hand-escaped documentation, and evidence marker names (lines 44–57).

**Evidence:** source inspection shows that whitespace or equivalent JSON escaping can fail these assertions while preserving decoded values. Conversely, finding a marker anywhere in the document does not prove it occupies the required field. The current renderer uses compact JSON; a differently formatted renderer was not executed in this review.

**Repair:** assert decoded fields, their locations, and independent expected contract shapes. Preserve exact-byte assertions only where canonical bytes are explicitly required. Cover equivalent rendering and a value placed in the wrong field.

## Comparisons worth keeping

- The control distribution's exact public executable inventory and required resource paths describe its installation contract.
- [Hosted-read forbidden authority](../../build-logic/src/main/kotlin/support/architecture/policy/HostedReadForbiddenAuthority.kt) compares JVM owner/member identities from compiled references. Those identify prohibited operations.
- [Hosted-read input hashes](../../build-logic/src/main/kotlin/support/architecture/gradle/HostedReadClassInputs.kt) bind evidence to currently captured class bytes; they do not freeze old compiler output as the only valid implementation.
- The [JSON contract scanner](../../build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinJsonContractScanner.kt) normalizes incidental syntax before fingerprinting debt exceptions. Its [tests](../../build-logic/src/test/kotlin/JsonContractGuardTest.kt) prove formatting/comment changes remain accepted while expanding a manual JSON builder fails. Do not broaden those allowances merely to pass a check.

A blanket ban on string comparisons would create another noisy guard. Apply the rule at the invariant boundary, with evidence for both acceptance and rejection.
