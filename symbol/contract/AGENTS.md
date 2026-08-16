# Symbol contract module guide

`:symbol:contract` owns detached host-neutral request policy for symbol reads. It does not own
IntelliJ scopes, PSI, indexes, query execution, mutation authority, or transport.

## Module map

- `SymbolSearchScope.kt` owns the generation-bound exact-file, module, source-set, Gradle-project,
  and workspace targets plus production/test, generated-source, and project-library read policy.
- `SymbolDiscoveryRequest.kt` owns file/class/symbol patterns and record, byte, work, and elapsed
  request budgets.
- `SymbolDiscoveryCandidate.kt` owns generation-bound detached workspace/external file identity,
  declaration locations, deterministic ordering, and canonical projection size.
- `SymbolDiscoveryOutcome.kt` owns bounded generation-bound batches, separate native/projection
  timings, closed qualified-completeness states, and the exact search scope retained by a batch.
- `SymbolDiscoveryOperations.kt` owns the public operation result, compiler port, and closed
  workspace/compiler rejection protocol for `symbol.discover`.
- `ExactDeclarationSelector.kt` owns batch-ordinal declaration selection, detached native
  declaration evidence, selector issuance, and proof of unchanged revalidation.
- `ExactDeclarationFingerprint.kt` owns the length-prefixed canonical scope/evidence encoding and
  opaque SHA-256 selector identity.
- `NativeRelationRequest.kt` owns the six one-hop relation families and the record, byte, work, and
  elapsed request budget carried with one exact selector.
- `NativeRelationFact.kt` owns exact related endpoints and occurrences bound to the subject
  selector's lease and retained scope.
- `NativeRelationOutcome.kt` owns deterministic bounded relation batches and makes terminal exact
  counts structurally distinct from qualified known-minimum counts.
- `NativeFastRead.kt` marks detached definition projections and owns their non-negative byte
  measure.

## Dependency boundary

- Production exports only `:kernel` and `:workspace:contract`.
- The published archive name is `symbol-contract`; keep it distinct from other nested `contract`
  projects so portable runtime assembly cannot overwrite a dependency.
- Do not import IntelliJ, Gradle, JDBC, filesystem, process, transport, legacy backend, adapter, or
  service-locator types.
- Readability policy never grants edit, write, or mutation authority.
- Library readability exists only on workspace-wide policy; narrower model owners cannot silently
  widen to every project library.
- Discovery candidates are suggestions, not exact selectors or mutation authority. Exact selection
  is possible only by ordinal from its owning batch; an IntelliJ adapter must then resolve that
  selection under the same root, generation, and scope before issuing an opaque selector.
- A native definition projector returns only `NativeDetachedDefinition`. It cannot return PSI,
  VFS, project, or search-scope objects.
- Exact selectors retain file, range, name, qualified-identity state, runtime declaration type, and
  a deterministic fingerprint. Consumers take the selector or a revalidation proof, never
  reconstruct authority from a name, FQN, file/offset tuple, or display projection.
- A capped, interrupted, dumb-mode, unsupported-provider, unsupported-item, or provider-failed
  query is qualified rather than complete.
- Relation requests inherit scope only from their exact selector. They carry no raw subject,
  independent scope, depth, or traversal state, and relation facts cannot bind endpoints issued
  under another lease or scope.
- An empty terminal relation batch proves absence only when the native provider completed without
  limitations. Every cap, unresolved target, unsupported item, dumb transition, provider failure,
  or nonterminal provider result preserves only a known minimum.

## Verification ladder

1. Run `./gradlew :symbol:contract:test --tests '*SourceRoot*PolicyTest' --tests '*SymbolDiscoveryContractTest' --tests '*ExactDeclarationSelectorContractTest' --tests '*NativeRelationContractTest'`.
2. Run `./gradlew :symbol:contract:test`.
3. Run direct IntelliJ adapter consumers after changing a public contract.
4. Run `./gradlew verifyKastArchitecture --configuration-cache`.
