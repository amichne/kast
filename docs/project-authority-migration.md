# Project authority migration

Status: incremental migration in progress
Implementation baseline: `309290b5fb6f793a014805d2ecd827c98007e894`

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

### Test-runtime/platform bytecode mismatch

`workspace:intellij-read` compiles against IDEA 262 platform classes built for Java 25, while its
Gradle tests currently execute on Java 21. A JUnit test class that mentions `ProjectFileIndex`
fails discovery with `UnsupportedClassVersionError` before an assertion runs. The current proof
therefore tests host-neutral detachment separately and uses compilation plus IntelliJ semantic
index evidence for the live adapter. Aligning the module test launcher with the pinned IDE runtime
would allow a true live `ProjectFileIndex` contract test.

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
