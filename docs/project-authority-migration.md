# Project authority migration

Status: incremental migration in progress
Implementation baseline: `9bc29ad1b5cdafd8f377b7bfd242e85f093f15cb`

This document records the authority rule, the current semantic-conversion inventory, completed
migration slices, and remaining gaps. It is not a declaration that the aggregate detached model is
already retired.

## Authority rule

Detachment may change representation. It must not change meaning.

Exactly one live authority owns each project fact:

| Fact | Authority | Kast's role |
| --- | --- | --- |
| Loaded modules, roots, root types, SDKs, and classpaths | IntelliJ loaded project model | Observe and detach only the facts an operation requires. |
| Concrete-file source, test, generated, library, ownership, and root membership | Request-local `ProjectFileIndex` | Detach the answer inside a read action; never reconstruct a competing live file index. |
| Android module, variant, and source-provider facts | Android project system when available | Preserve Android identity in a separate adapter; absence is explicit. |
| Imported Gradle build/project/source-set identity | Imported Gradle model | Preserve exact imported identity without synthesizing conventional names. |
| Search, topology, diagnostic, relation, and mutation policy | The executing Kast operation | Reduce authority facts only where the requested operation defines which distinctions matter. |
| Persisted project-derived state | No current-project authority | Treat it as generation-bound evidence that requires a current matching lease/generation. |

The workspace adapters may expose immutable Kast types. Those types carry proof and provenance;
they do not become a second definition of the loaded project.

## Completed slices

### Request-local concrete-file classification

`IntellijProjectFileIndexClassifier` is the only production owner of
`ProjectFileIndex.getInstance`.

For a source file it detaches:

- exact virtual-file URL;
- exact IntelliJ module name;
- exact content-root and source-root URLs;
- production/test membership reported by IntelliJ;
- authored/generated membership reported by IntelliJ;
- explicit `PROJECT_FILE_INDEX` authority.

It also distinguishes exact library membership from other non-source membership. Missing source
owner or root facts produce a closed `ProjectFileClassificationFailure`; they are not converted to
"outside source content."

The classification is request-local. No `ProjectFileIndex`, `Project`, `Module`, or `VirtualFile`
escapes in the detached result, and no project-wide classification is persisted.

### Mechanical ProjectFileIndex ownership

The bytecode architecture scanner classifies every direct JVM reference to
`com.intellij.openapi.roots.ProjectFileIndex` as `PROJECT_FILE_INDEX_AUTHORITY`. The canonical
policy grants that effect only to `workspace:intellij-read`, and the policy validator fixes that
module as its exclusive owner. A direct call reintroduced in diagnostics, relations, or another
IntelliJ consumer now fails `verifyKastArchitecture` even if source review misses it.

### Bounded endpoint readiness re-observation

Endpoint preparation remains fail closed when cached IntelliJ or Gradle model evidence is
incomplete, but a deferred observation no longer depends on a later readiness event arriving in a
particular order. The pure endpoint coordinator issues an opaque, single-use scheduled-retry
capability with a finite cadence: 250 milliseconds, 1 second, then a 3-second capped quiescent
interval. A real readiness signal consumes the awaiting state immediately; the older timer then
coalesces as stale and cannot queue or launch a duplicate attempt.

The project-service coroutine scope owns the delay, so project or plugin disposal cancels it. The
previous one-off three-second service timer was removed because retry policy now applies to every
typed deferred outcome instead of one startup callback.

### Single retained project-read epoch authority

Existing-Project policy validation is now distinct from `AdmittedIdeProject` authority
installation. The endpoint project-service session installs and retains one read-epoch source and
reuses it across deferred attempts. Hosted topology, source-state, change, relation, and diagnostic
factories use `ExistingProjectValidation`, which applies the same exact-root, Gradle-model,
smart-mode, K2, and compatibility policy without accepting an epoch-source factory.

This reduces successful cold-start read-epoch subscriptions from twelve to two: the retained
source owns one workspace-model listener and one root-filtered VFS listener. Deferred attempts that
reach admission do not add another pair. Compiled hosted-factory tests reject a validation-only
path that references `AdmittedIdeProject`.

The compiled architecture gate classifies the `AdmittedIdeProjectSession.admit` JVM member,
including its Kotlin value-class-mangled form, as `PROJECT_READ_EPOCH_AUTHORITY`. `ide-plugin` is
its exclusive policy owner, so a future direct call from topology, diagnostics, relations, change,
or another validation-only adapter fails `verifyKastArchitecture`.

Hosted mutation storage now refines raw SQLite connections into one typed initialized-recovery
capability. `SqliteDurableChangeAuthority.openHosted` returns both the durable change authority and
its retained recovery journal from that proof, while the narrower `open` still exposes only change
authority. Hosted runtime no longer opens a second journal. Together with generation resumption,
normal startup recovery-schema initialization passes fall from four to two. A production-bytecode
test fixes the hosted composition boundary at one aggregate authority open and zero direct journal
opens.

### First consumers

The diagnostic adapter now uses the detached classification for concrete-file source admission.
An exact non-source result remains `OUTSIDE_SOURCE_CONTENT`; an incomplete/rejected authority
observation remains `ANALYSIS_UNAVAILABLE`.

Relation occurrence provenance now comes from the same authority:

- exact library membership becomes `K2_PROJECT_LIBRARY`;
- exact generated-source membership becomes `K2_GENERATED_SOURCE`;
- exact non-generated source membership becomes `K2_AUTHORED_SOURCE`.

The previous longest-source-root-prefix reconstruction was deleted. Relation provenance no longer
uses paths stored in `WorkspaceSearchScopeModel` to rediscover a fact that IntelliJ can answer.

## Semantic-conversion inventory

The classifications below use the migration categories `LOSSLESS REPRESENTATION`, `OPERATION
POLICY`, `SEMANTIC INFERENCE`, `COMPATIBILITY PROJECTION`, and `OBSOLETE`.

| Producer or conversion | Current classification | Information and required action |
| --- | --- | --- |
| `LiveDetachedModelCapture.observeInsideRead` reads the admitted existing project, Gradle cache state, and modules. | `LOSSLESS REPRESENTATION` plus project-admission `OPERATION POLICY` | Keep the existing-project/read-action/lifecycle boundary. Split observations by capability so one unrelated missing fact does not reject every operation. |
| `LiveDetachedModelCapture.observeSourceRoots` maps four Java/JPS root types to `DetachedSourceRootKind`. | `SEMANTIC INFERENCE` | It rejects or erases other IntelliJ root types before an operation can decide whether they matter. Replace it with exact IDE root-type identity facts. |
| The same source-root capture reads generated flags from Java source/resource properties. | `LOSSLESS REPRESENTATION` | Preserve this behavior. Concrete-file consumers should prefer live `ProjectFileIndex.isInGeneratedSources`. |
| `observeModule` reads IntelliJ module name plus external Gradle build/project identities. | `LOSSLESS REPRESENTATION` followed by aggregate `SEMANTIC INFERENCE` | Exact identities are valuable. Requiring every source-bearing module to be Gradle-owned is an aggregate-model policy to retire or localize. |
| `DetachedIdeWorkspaceModelRefinement` converts exact absolute roots to workspace-relative identities. | `LOSSLESS REPRESENTATION` when the exact root is retained | Keep exact-root containment and bounded identity proof. Do not use this representation to redefine file membership. |
| The same refinement requires non-empty modules/source roots/classpaths/SDK, unique module names, and one generic root owner. | Mixed `OPERATION POLICY` and `SEMANTIC INFERENCE` | Move each requirement to the operation that needs it. A missing classpath must not reject an operation that only needs file membership. |
| `HostedDetachedScopeModel.hostedSourceSet` maps production/resource to `main` and test/test-resource to `test`. | `SEMANTIC INFERENCE` | Next priority. An inferred source set must become an explicitly qualified compatibility approximation or disappear from this path. |
| `HostedDetachedScopeModel.hostedSourceKind` collapses resource roots into production/test source. | `COMPATIBILITY PROJECTION` | Retain only at a named scope-policy boundary and preserve the original IDE root type separately. |
| `HostedWorkspaceAdmission.sourceSet` and `scopeKind` repeat the same mappings. | Duplicate `SEMANTIC INFERENCE` / `COMPATIBILITY PROJECTION` | Consolidate or retire with the hosted scope projection; the duplicate can drift independently. |
| `WorkspaceSearchScopeModel.compile` trims primitive names, admits canonical roots, and rejects ambiguous/coherency failures. | Useful refinement plus generalized `OPERATION POLICY` | Keep typed validation where a consumer requests this model. Stop presenting it as universal project authority. |
| `InstalledGradleSourceRootCapture` reads source-set names from the imported Gradle model. | `LOSSLESS REPRESENTATION` | This is the valid authority for exact Gradle source-set identity. Preserve provenance that distinguishes it from approximations. |
| `InstalledGradleModelProjection` converts captured imported Gradle boundaries to scope/publication models. | `COMPATIBILITY PROJECTION` | Safe only while generation and imported-model authority remain explicit. Do not reuse it as current live project truth. |
| Symbol scope compilers consume `WorkspaceSearchScopeModel` and apply test/generated/library request policy. | `OPERATION POLICY` | Policy belongs here. Migrate their inputs from the aggregate model to the smallest authority observations they require. |
| Relation scope compilation consumes the generalized scope model. | `OPERATION POLICY` | Search-scope policy remains, but concrete occurrence provenance now delegates to live IntelliJ authority. |
| Diagnostic concrete-file source admission previously called a Boolean directly. | Replaced by `LOSSLESS REPRESENTATION` | Completed in the first slice. |
| Relation authored/generated provenance previously longest-prefix matched stored roots. | `OBSOLETE` | Deleted in the second slice. |
| Runtime/IDE composition transports `DetachedIdeWorkspaceModel` and compiles hosted scope. | `COMPATIBILITY PROJECTION` | Migrate one capability at a time; do not replace it with another universal snapshot. |
| Persisted topology/workspace evidence is bound to semantic state/generation. | Generation-bound evidence | Retain generation checks. Audit every reader before deleting aggregate model types. |

### Producer and consumer file inventory

Aggregate detached-model producers/refiners:

- `workspace/intellij-read/.../LiveDetachedModelCapture.kt`
- `workspace/intellij-read/.../DetachedIdeWorkspaceModel.kt`
- `workspace/intellij-read/.../DetachedIdeWorkspaceModelRefinement.kt`
- `workspace/intellij-read/.../DetachedModelCapture.kt`
- `workspace/intellij-read/.../ExistingProjectAdmission.kt`

Generic scope producers/projections:

- `runtime/ide-read/.../HostedDetachedScopeModel.kt`
- `ide-plugin/.../HostedWorkspaceAdmission.kt`
- `workspace/intellij/.../InstalledGradleModelCapture.kt`
- `workspace/intellij/.../InstalledGradleSemanticIdentity.kt`
- `workspace/intellij/.../provenance/InstalledGradleSourceRootCapture.kt`
- `runtime/composition/.../InstalledGradleModelProjection.kt`
- `runtime/composition/.../InstalledWorkspaceModelAdapter.kt`

Current scope consumers:

- symbol discovery/exact adapters and `IntellijSearchScopeCompiler`;
- relation compiler/port and `IntellijRelationScopeCompiler`;
- hosted IDE-read production composition;
- hosted workspace admission and installed workspace composition.

This is the migration work list. A type may remain temporarily as a compatibility projection, but
new consumers must not treat it as a source of current project truth.

## Gaps and oddities

### Source-set identity is still silently invented

Two hosted paths still produce the literal names `main` and `test` from generic IntelliJ root
categories. The resulting `WorkspaceSourceSetName` is indistinguishable from a name supplied by an
imported Gradle source-set model. This is the highest-priority remaining authority bug.

The next change should introduce a closed source-set authority/qualification state and thread it
through `WorkspaceSourceRootBoundary`, `ModelOwnedSourceRoot`, and publication evidence. Exact
Gradle names and compatibility approximations must have different types or variants before the
literal mapping is retained anywhere.

### Android authority is absent

There is no separate Android project-system observation adapter in this repository snapshot.
Variant, build-type, flavor, and source-provider identities therefore cannot yet survive
detachment. Do not infer them from directories or Gradle task/source-set names. Add the adapter only
after the generic source-set representation can preserve specialized authority.

### The first classifier is intentionally narrow

The new classifier preserves the facts required by the migrated diagnostic and relation paths. It
does not yet detach excluded state, general project/content membership, unloaded module identity,
or separate library-class/library-source membership. Add those variants when a concrete operation
needs them; do not prebuild a project-wide index mirror.

### Aggregate capture still couples unrelated capabilities

The detached workspace model can reject the whole observation for missing Gradle ownership, SDK,
classpath, source roots, or other bounds. Capability-specific observations should prevent an
operation from depending on facts it never requested.

This also remains the largest cold-start cost and availability risk. Bounded re-observation fixes
the lost-wakeup failure mode; it does not make aggregate hosted-runtime preparation incremental.
The next cold-start slice should measure each preparation phase and move descriptor/socket
publication behind the smallest capability set that can honestly be advertised, without
manufacturing partial readiness.

### Test-runtime/platform bytecode mismatch

`workspace:intellij-read` and the rest of the build now compile and test on Java 25, matching the
IDEA 262 platform bytecode baseline. JUnit can therefore discover contracts that mention live
platform types without an `UnsupportedClassVersionError`; host-neutral detachment tests remain
separate from live-adapter tests because they prove different boundaries.

### Kast app-server semantic tools were unavailable during this slice

The active Codex toolset exposed no Kast app-server operations. To respect the app-server-only
constraint, no Kast CLI command was invoked. IntelliJ-index symbol/reference/diagnostic operations
were used as the documented fallback. Restore the Kast tool surface before the next migration so
Kast diagnostics and installed hosted journeys can be captured through the requested boundary.

## Next waves

1. Make source-set authority explicit and qualify or remove the hosted `main`/`test` approximation.
2. Preserve exact IntelliJ root-type identity instead of rejecting every non-Java root vocabulary.
3. Migrate symbol scope construction from the aggregate model to operation-specific observations.
4. Add the minimal Android project-system adapter for module, selected variant, source provider,
   and root association facts.
5. Extend request-local file classification only for concrete excluded/content/ownership use cases.
6. Retire aggregate capture requirements as their last consumers move, then delete compatibility
   projections rather than preserving them indefinitely.
7. Run the installed hosted tool journeys through the Codex app-server toolset once that surface is
   available, including staleness after workspace-model/VFS changes.
