# Plan: Gradle Model-Input Symlinks and Sidecar Heap

Status: implementation plan
Base: `main` at `2941ec2d89353871225bcbf38fcf004ea77dedfc` (`v0.36.0`)
Scope: two startup defects observed against the installed `v0.36.0` product

## Goal

Make Kast start successfully for repositories that use workspace-contained symbolic links in conventional Gradle model inputs, and make the IntelliJ sidecar heap explicitly configurable without weakening process isolation or conflating launch policy with semantic cache identity.

The change is complete only when both fixes preserve Kast's existing invariants: proof-preserving refinement, finite typed failures, fail-closed boundaries, deterministic model-input identity, isolated sidecar launch, and evidence-backed startup diagnostics.

## Observed defects

### 1. Workspace-contained Gradle model-input symlinks are rejected

`InstalledGradleModelInputs.capture()` currently rejects selected symbolic links. `selected()` includes Gradle scripts/properties/catalogs and all entries beneath `buildSrc` or `build-logic`, so legitimate repository-internal links such as shared wrapper files and shared Gradle configuration are treated as `MODEL_INPUTS_UNAVAILABLE` before Gradle import begins.

Materializing the valid links demonstrated that model-input admission then succeeds and real Gradle import begins. That experiment does not prove that every original link was valid: five already-dangling links were also removed. The product fix must therefore admit valid workspace-contained links while continuing to reject dangling, cyclic, inaccessible, unsupported, or workspace-escaping links.

The current failure is also projected misleadingly. Model-input capture occurs after successful Gradle JVM selection but before the bootstrap phase advances to project import, and `MODEL_INPUTS_UNAVAILABLE` is collapsed into generic `MODEL_UNAVAILABLE`. The public diagnostic can consequently report a model failure while the phase still says `selecting-gradle-jvm`.

### 2. IntelliJ sidecar heap is hard-coded to 1536 MiB

`indexer/src/main/scripts/kast-indexer` currently launches the IntelliJ sidecar with `-Xms256m -Xmx1536m`. The launcher deliberately removes ambient `JAVA_OPTS`, `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, and `JDK_JAVA_OPTIONS`, and the CLI clears the child environment before installing its admitted environment. There is therefore no supported way for an installed user to raise the sidecar heap.

On the representative repository, startup reached real repository import after the symlink workaround and the sidecar repeatedly failed with `java.lang.OutOfMemoryError: Java heap space`. This proves that the current 1536 MiB cap is insufficient for that observed workload. It does not prove that 8 GiB is universally sufficient, that seeding caused the memory pressure, or that a memory leak exists.

## Non-goals

- Do not materialize, rewrite, delete, or otherwise mutate repository symlinks.
- Do not admit symlinks that resolve outside the canonical workspace.
- Do not silently ignore dangling selected model inputs.
- Do not change the conventional model-input selection policy in the symlink fix.
- Do not add arbitrary JVM argument passthrough.
- Do not propagate ambient `JAVA_OPTS` or weaken launcher isolation.
- Do not make heap size part of semantic cache identity or require reseeding/rebuilding when it changes.
- Do not raise the default heap merely because one repository failed at 1536 MiB.
- Do not infer a cache-corruption defect from a cache remaining `refreshing` after an intentionally interrupted startup.

## Required invariants

### Model-input identity

Every selected input must have a deterministic, workspace-contained resolution and retained evidence of both its logical identity and the content observed through it.

A successful capture must preserve enough evidence that any of these changes invalidate the imported-model authority:

- selected file content changes;
- a logical symlink is retargeted, including to equal content;
- the resolved target changes kind;
- a selected descendant is added or removed beneath a linked directory;
- a previously valid resolution becomes dangling, cyclic, inaccessible, unsupported, or workspace-escaping.

Two logical aliases to the same target remain distinct model inputs. Shared targets are not themselves cycles.

### Sidecar launch configuration

Heap configuration must follow the proof-preserving path:

```text
raw configuration
-> parsed heap request
-> admitted heap size
-> sidecar launch capability
-> canonical private launcher argument
-> exactly one -Xmx argument
-> observed running heap evidence
```

An explicitly invalid heap value rejects before process launch. An absent value selects the existing default. Arbitrary caller JVM options remain excluded.

### Diagnostics

Once Gradle JVM selection succeeds, later failures must not erase that proof or be reported as JVM-selection failures. Model-input capture must have its own startup phase and retain the specific finite cause and logical input path through the public bootstrap projection.

## Work graph

```text
S1 model-input resolution + identity
  -> S2 model-input startup diagnostics

H1 typed heap configuration
  -> H2 launch propagation + effective-heap evidence

S2 + H2
  -> A installed acceptance + documentation
```

The S and H lanes are independent until installed acceptance.

---

## S1 — Admit workspace-contained Gradle model-input symlinks

### Goal

Replace the blanket `attributes.isSymbolicLink` rejection with a narrow resolver that admits selected regular files and selected directory aliases only when their complete resolution remains within the canonical workspace.

### Primary implementation locations

- `workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleModelInputs.kt`
- `workspace/intellij/src/test/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleModelInputsTest.kt`

### Design

Keep `InstalledGradleModelInputs` as the authority for imported Gradle-input evidence. Do not introduce a generic filesystem abstraction.

Introduce privately constructible domain values for the evidence that crosses capture/current boundaries. The exact names may follow local conventions, but the representation must retain:

- logical workspace-relative input path;
- encountered link path(s) and stored link target(s);
- resolved workspace-relative target;
- resolved entry kind;
- content identity for a selected file, or deterministic selected-descendant identities for a selected directory.

Resolution must inspect each link boundary rather than calling an unrestricted `toRealPath()` and checking containment only after the fact. Reject before consuming target content if resolution escapes the workspace.

Directory-cycle detection must use active traversal ancestry, not a global visited-target set. This permits two legitimate logical aliases to one shared Gradle directory while rejecting recursive traversal.

Preserve current excluded directories and unrelated-symlink behavior.

### Closed failures

Replace generic symlink-to-`IOException` collapse with finite capture failures sufficient to distinguish at least:

- target outside workspace;
- link cycle;
- target missing;
- unreadable input;
- unsupported entry.

The failure evidence must retain the logical workspace-relative input path. If the existing public `InstalledGradleModelCaptureFailure` is too coarse to carry this evidence, introduce a stronger internal capture failure and map it exhaustively at the existing boundary rather than encoding details into strings.

### RED proof

Add focused tests that currently fail:

```sh
./gradlew :workspace:intellij:test --tests '*InstalledGradleModelInputsTest'
```

Required new cases:

1. selected file symlink -> regular file inside workspace succeeds;
2. selected directory symlink -> directory inside workspace succeeds;
3. two logical included-build aliases to one shared target both succeed and remain distinct;
4. target content mutation invalidates prior authority;
5. retargeting a link to equal-content target invalidates prior authority;
6. adding/removing selected descendants under a linked directory invalidates prior authority;
7. direct and chained workspace escape reject;
8. file and directory cycles reject;
9. dangling selected link rejects;
10. unrelated links and excluded outputs retain existing behavior.

### GREEN proof

The focused test command passes with no repository mutation and no weakening of outside-workspace rejection.

### Forbidden work

- changing the selected-input policy;
- copying target content into the logical link path;
- treating target content alone as identity;
- using a global visited set as cycle detection;
- skipping selected dangling links;
- following arbitrary links outside the selected Gradle-input traversal.

### Completion receipt

Evidence must identify the exact head, focused test command, passing test set, and relevant artifact/source digests. S2 may depend only on a passing S1 receipt.

---

## S2 — Preserve model-input failure cause through startup

### Goal

Make model-input capture an explicit bootstrap boundary and preserve its finite cause through the installed runtime diagnostic instead of exposing generic `model-unavailable` during `selecting-gradle-jvm`.

### Primary implementation locations

- `workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt`
- `runtime/composition/.../InstalledRuntimeAssembly.kt`
- `runtime/composition/.../InstalledKastRuntime.kt`
- `distribution/contract/.../bootstrap/SemanticRuntimeBootstrapPhase.kt`
- `indexer/.../InstalledIndexerBootstrapProgress.kt`
- existing bootstrap-state publisher/projection tests

### Design

Add one explicit model-input-capture phase immediately before `InstalledGradleModelInputs.capture(workspaceRoot)`.

Preserve the already successful Gradle JVM selection report when advancing to that phase.

Project S1's finite failure evidence through the existing bootstrap contract. Do not create a parallel diagnostic protocol. The public result must identify:

- phase: model-input capture;
- finite reason;
- logical input path when the failure belongs to an input;
- corrective action appropriate to the model input rather than JVM selection or generic cache rebuild.

The exact serialized names are part of implementation review, but they must be schema-backed and exhaustively mapped.

### RED proof

Add a focused bootstrap projection test proving that an escaping/dangling/cyclic selected model input currently loses its cause or appears under the wrong phase.

Run the narrowest owning module tests plus the workspace test from S1.

### GREEN proof

The same fixture produces a typed model-input-capture failure with the logical path and reason, while the retained JVM-selection evidence remains present and unchanged.

### Forbidden work

- generic error-message parsing;
- replacing finite failure values with exception text;
- mapping a model-input failure to `SELECT_GRADLE_JVM`;
- recommending cache rebuild for a repository input defect;
- dropping the previously established JVM selection evidence.

### Completion receipt

Bind the exact serialized contract/schema artifact and the projection test evidence in addition to the standard receipt inputs.

---

## H1 — Admit a typed sidecar heap configuration

### Goal

Expose one supported sidecar maximum-heap setting and parse it once into a constrained domain value before startup effects.

### Proposed public interface

```sh
KAST_INDEXER_MAX_HEAP=8g kast start
```

Use the same key in Kast's existing saved configuration mechanism. The exact key may change during implementation only if current configuration conventions provide a materially better canonical name; there must still be one public setting.

### Ownership

Prefer the shared constrained value in `distribution:contract` if both installed CLI configuration and indexer launch projection require it. Keep raw environment/config parsing at the installed CLI boundary.

### Admission contract

Support positive integral `m` and `g` values. Normalize to one canonical internal unit such as MiB. Reject:

- blank values;
- missing units;
- fractional values;
- zero/negative values;
- overflow;
- values below the retained initial heap requirement.

Do not silently substitute the default for an explicitly malformed value.

Retain the current 1536 MiB maximum as the absent-value default for this patch. The observed failure establishes a need for configurability, not a new universal default.

Define precedence explicitly and test it. Saved configuration and process environment must not produce two simultaneously authoritative heap values.

### RED proof

Add focused tests for absent/default, explicit override, precedence, malformed values, overflow, and lower-than-initial-heap rejection.

### GREEN proof

All configuration paths yield either one admitted `IndexerHeapSize` or one finite rejection before any sidecar process effect.

### Forbidden work

- `JAVA_OPTS` passthrough;
- arbitrary JVM argument lists;
- nullable/Boolean validity state attached to a raw string;
- multiple independent defaults;
- adding heap size to semantic runtime/cache identity.

### Completion receipt

Bind the admitted configuration contract tests and the exact default/normalization policy.

---

## H2 — Propagate admitted heap through sidecar launch

### Goal

Carry H1's admitted heap value through the existing launch command to the shell boundary and emit exactly one sidecar `-Xmx` argument without expanding ambient environment authority.

### Primary implementation locations

- `cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/RuntimeBoundary.kt`
- sidecar launch-context construction
- direct and launchd runtime process paths
- `indexer/src/main/scripts/kast-indexer`
- `indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerMain.kt`
- `indexer/src/test/scripts/test-kast-indexer-isolation.sh`

### Design

Carry the admitted value as part of `SidecarLaunchContext`/`IndexerLaunchCommand` and project a canonical private launcher argument, for example:

```text
--max-heap-mib=8192
```

The shell launcher must require exactly one canonical value and translate it to exactly one `-Xmx...m`. It must not parse the public `8g` spelling itself.

Add the private option to the indexer's launcher-owned prefix set so it cannot leak into IntelliJ application arguments.

Keep `-Xms256m` unless measurement demonstrates a separate need to change it.

Direct and launchd startup must consume the same admitted command representation. Do not create a launchd-only environment override.

### Effective configuration evidence

Expose bounded startup evidence containing:

- requested/admitted maximum heap;
- JVM-observed maximum heap after process startup.

Do not record arbitrary JVM arguments or environment contents.

If a sidecar is already running, a changed configured heap does not mutate that process. Passive/runtime status must not claim the new value is active. The changed value takes effect on the next process launch.

### RED proof

Extend `test-kast-indexer-isolation.sh`, which already captures the exact fake-JBR argument vector and verifies ambient JVM-option isolation.

Required cases:

- absent configuration projects exactly one `-Xmx1536m`;
- explicit 8 GiB configuration projects exactly one `-Xmx8192m`;
- missing/duplicate/malformed private heap arguments never reach Java;
- ambient `JAVA_OPTS`, `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, and `JDK_JAVA_OPTIONS` remain blocked;
- installed payload and IDEA home remain unmodified;
- launcher-owned heap option does not reach IntelliJ application arguments.

Run:

```sh
./gradlew :indexer:testIndexerLauncherIsolation
```

Add the narrow CLI tests necessary to prove `IndexerLaunchCommand` propagation and direct/launchd parity.

### GREEN proof

Captured Java arguments contain exactly the admitted heap and no ambient JVM options. Startup evidence reports the same admitted value and compatible JVM-observed maximum.

### Forbidden work

- reading public heap configuration directly in `kast-indexer`;
- retaining a second independent hard-coded maximum in the shell;
- changing Gradle daemon heap;
- changing the user's interactive IDEA heap;
- rebuilding/reseeding solely because heap configuration changed.

### Completion receipt

Bind launcher argument capture, CLI command-projection tests, and effective-heap evidence tests.

---

## A — Installed acceptance and documentation

### Goal

Prove both fixes against installed-product boundaries without converting the repository workaround into product behavior.

### Acceptance fixture: symlinks

Create or extend the smallest installed-product fixture that represents:

- a root Gradle configuration;
- `build-logic` or an included build;
- workspace-contained links for shared `gradle.properties`, wrapper directory/properties, and wrapper script;
- at least one rejected outside/dangling/cyclic case in focused tests rather than the successful journey.

The successful installed journey must leave links as links before and after startup.

### Representative repository acceptance

On the affected repository/environment, restore valid internal links rather than materializing them. Treat the five known dangling links separately: either repair their repository targets or retain the expected typed `TargetMissing` rejection. Do not delete them as part of Kast startup.

Launch with an explicit diagnostic heap, initially 8 GiB unless host constraints require another admitted value. Confirm:

1. the installed product reports the requested heap as admitted;
2. the running JVM reports a compatible effective maximum;
3. Gradle JVM selection succeeds;
4. model-input admission succeeds for valid workspace-contained links;
5. real Gradle import completes;
6. IntelliJ indexing/model capture completes;
7. runtime reaches ready;
8. repository links remain unchanged.

If the sidecar still exhausts memory with the confirmed higher cap, H1/H2 may still be correct, but the representative-workload defect remains unresolved and requires allocation investigation. Do not respond by silently increasing the default again.

### Documentation

Update troubleshooting/configuration documentation to cover:

- valid workspace-contained Gradle input links;
- finite failures for outside/dangling/cyclic links;
- the sidecar heap setting, default, accepted syntax, and restart semantics;
- the distinction between sidecar heap and Gradle daemon