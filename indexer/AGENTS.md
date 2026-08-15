# Indexer module guide

`indexer` is Kast's single compiler-backed runtime. It launches an isolated
headless IntelliJ Platform process for one exact workspace root, imports the
Gradle model, performs Kotlin/Java PSI and K2 work, persists semantic evidence,
and serves the `analysis-server` backend. It is a private runtime application,
not a foreground IDE plugin.

## Module map

- `io.github.amichne.kast.indexer` owns raw starter-argument parsing, the
  application-classloader launcher, IntelliJ `ApplicationStarter` handoff,
  runtime assembly, and bounded profiling.
- `indexer/gradle/bootstrap` and `gradle/settlement` own Gradle import
  configuration, linked-build admission, progress-aware model settlement, and
  compiler-ready module evidence. Java bridges isolate IntelliJ APIs whose
  signatures are awkward at the Kotlin boundary.
- `indexer/project` opens the exact workspace and returns typed project/model
  bootstrap state.
- `idea/runtime/service` assembles the backend, source-index store, analysis
  server, indexing runtime, observers, and shutdown. Its `transition` subtree
  runs the event-driven reconciliation worker and publishes readiness.
- `idea/transition` owns host adapters for workspace signals, freshness claims,
  Git worktree guards, semantic input identity, VFS observation, and publication
  capabilities. `:workspace:service` owns host-neutral transition coordination,
  retry/block state, and the publication protocol.
- `idea/workspace` owns Gradle project/source-set production/test kind and
  authored/generated provenance, workspace identity, file inventory/paging/snapshots,
  indexing scope, stage versions, hydration, and project indexing.
- `idea/snapshot` binds committed Git tree and build classpath identity to
  repository snapshot publication and worktree overlay selection.
- `idea/backend` implements `AnalysisBackend`. Its subtrees own diagnostics,
  exact mutations, references/relationships, semantic admission/graph, and
  workspace operations.
- `idea/edit` and `idea/mutation` own exact text-image planning, durable native
  file effects, scratch recovery, cancellation, source proof, and workspace
  create/replace/delete operations.
- `idea/semantic` and `shared` own read-action admission, reference/type/call
  traversal, telemetry, PSI scanning, diagnostics conversion, outlines, and
  bounded hierarchy traversal.
- `src/main/resources/META-INF/plugin.xml` is the private classloader
  descriptor; `src/main/scripts` and Gradle tasks assemble the portable
  runtime.

## Dependency boundary

- Runtime-facing `analysis`, `index-store`, `workspace`, `symbol`, `evidence`, and
  operation-specific `change` modules are `compileOnly` inputs and explicit
  `indexerPluginRuntime` payloads. The private plugin payload owns these jars
  at runtime.
- IntelliJ core libraries belong to the launcher runtime; Kotlin, Java, Gradle,
  and other platform plugin libraries stay in the packaged IDEA home. Never
  duplicate platform-plugin-owned classes in the private Kast payload.
- `KastIndexerMain` and `KastIndexerBootstrap` run on the application
  classloader and must remain free of IntelliJ, Kotlin-plugin, Gradle-plugin,
  analysis-server, and index-store linkage before reflective handoff.
- `META-INF/plugin.xml` exists only to load the isolated private runtime. Do
  not add installable-plugin metadata, update feeds, signing, Marketplace
  publication, or foreground-project lifecycle.
- An eligible installed IntelliJ IDEA or Android Studio supplies matched runtime
  libraries; its foreground process is never a semantic or lifecycle authority.
- Process selection, semantic demand, setup, runtime-epoch ownership, and public
  CLI rendering remain outside this module. This module owns the already admitted
  isolated process and its internal resources.

## Runtime invariants

- A bare checkout is normal. Explicit semantic demand opens the exact root,
  configures one Gradle import authority, waits for a settled linked model and
  smart/compiler-ready state, then reports typed readiness or a blocker.
  Checked-in `.idea` state and a foreground IDE are irrelevant.
- Preserve separate runtime, model, source, reference, graph, and mutation
  readiness. `WorkspaceSemanticGate` admits only evidence from the current
  published workspace generation; blocked or moving lanes cannot be rendered
  as ready.
- The first public native symbol route compiles the imported model, runs one
  bounded IntelliJ read, and returns detached definitions with generation,
  completeness, stage, work, byte, and selector-handle evidence.
- Workspace events enter through `WorkspaceTransitionIngress`. Coalesce
  compatible work while retaining the newest source-content freshness claims,
  build semantic identity, and recovery-audit demand. Do not start parallel
  VFS refresh, Gradle import, or indexing loops for the same transition.
- Transition publication is typed and owner-bound: begin, reconcile, prepare,
  commit, or discard. A generation becomes visible only after workspace
  identity, model, source stages, references, graph/blocker, and store
  publication agree.
- Observe build inputs before and after refresh/import. If settings, build
  scripts, version catalogs, source roots, compiler SDK/classpath, or linked
  Gradle identity move during the transition, invalidate or retry through the
  closed transition outcome; never publish the earlier observation.
- Git worktree transition markers and linked-worktree registration are exact-
  root gates. Unreadable, ambiguous, changed, symlinked, or mismatched Git
  evidence fails closed. Read-only Git probes must not mutate the checkout.
- Gradle ownership comes from the linked project model and remains qualified by
  build root, project path, and source set. Missing ownership and package
  parsing remain typed unproven evidence rather than empty/root defaults.
- Convert `PsiFile`, `KtFile`, IntelliJ `FqName`, modules, and analysis-session
  objects to host-neutral `index-store` values inside the read action. No
  IntelliJ object crosses the persistence boundary or survives in continuation
  state.
- Capture symbol identity, references, relationship coverage, diagnostics
  hashes, and semantic generation in one compatible read epoch. On generation
  drift, invalidate/reconcile and retry only within the operation's bound.
- Relationship continuation state owns only opaque host state required for the
  next page, is query/subject/generation bound, and is disposed exactly once.
  Unsupported subject kinds perform no provider work.
- Exact mutation planning must prove target/owner/source-root admission,
  preimage, semantic generation, signature compatibility, complete outbound
  references, and postcondition. `MutationAttemptGate` serializes one exact-
  root attempt; stale attempt IDs, images, selectors, or generations conflict.
- Add-declaration planning crosses the legacy backend only through
  `IntellijAddDeclarationPlanner`. It returns a detached, generation-bound
  `PlannedAddDeclaration` with one non-empty declared write set, exact Gradle
  ownership, canonical compiler evidence, semantic delta, and verification
  obligations. The `change:contract`, `change:plan:spi`, and
  `change:plan:intellij` classpaths remain read-only and must not acquire source
  mutation authority.
- The public add-declaration planner persists only after the semantic read lease
  has been validated and released. Runtime composition opens the workspace-scoped
  SQLite journal; the backend consumes only `AddDeclarationPlanPersistence` and
  projects legacy compiler evidence only from a stored or identical existing record.
- Native mutation effects preserve hard exclusions, symlink/root containment,
  durable parent/file writes, cancellation, and totalized scratch recovery.
  A successful edit is not a substitute for compiler postcondition proof.
- The KIP-030 add-declaration physical protocol is pinned by
  `.agents/arch/kast-add-declaration-intellij-protocol.json`. Preparation and
  every semantic/search decision remain outside the command. The command may
  only insert PSI, reformat whitespace, and commit the declared target; save
  follows afterward. Headless undo, reference shortening, Android Studio, and
  unpinned builds remain explicit unsupported evidence until separately proven.
- Repository snapshots bind committed tree, classpath, schema, and producer
  identity. Worktree overlays retain dirty shards/tombstones and may read an
  immutable validated base only; stale or mismatched bases are revoked.
- `RunningAnalysisServer` closes its backend. Runtime shutdown first stops
  transition/indexing work, then closes server/backend-held state, and finally
  closes separately owned source-index/snapshot resources. Every repeated
  close path is idempotent.
- Profiling is opt-in, exact-root and source-head bound, finite, and owned by
  `RunningKastIndexer`. Requested artifacts must finalize as non-empty regular
  files or return the typed finalization failure.

## Packaging invariants

- Keep launcher classes only in the launcher jar and implementation classes in
  the private plugin jar under `idea-home/plugins/kast-indexer/lib`.
- `runtime-libs/classpath.txt` contains launcher/platform runtime entries, not
  private payload jars. The portable distribution must not ship a fat jar.
- Keep `VerifyClasspathLayoutTask` checks for required/forbidden class entries,
  jar ownership, plugin descriptors, and platform plugin classes whenever the
  payload changes.
- Generated indexer version resources and wrapper scripts are build outputs;
  edit their Gradle/task source rather than generated files.

## Verification ladder

1. Run the smallest relevant class:
   `./gradlew :indexer:test --tests '<fully.qualified.TestClass>'`.
    For KIP-030 run
    `io.github.amichne.kast.idea.backend.contract.mutation.addition.AddDeclarationIntellijProtocolTest`
    plus `.agents/arch/test-kast-add-declaration-intellij-protocol.py`.
    For KIP-031 also run `:change:contract:test`,
    `:change:plan:intellij:test`, and
    `io.github.amichne.kast.idea.ExactAdditionPlannerContractTest`.
2. Run `./gradlew :indexer:test` for Kotlin/Java source changes. The pinned IDEA
   distribution and platform plugins must be available.
3. Run excluded suites explicitly when their risk applies, for example
   `./gradlew :indexer:test -PincludeTags=concurrency` or
   `-PincludeTags=performance`; do not claim them from the default suite.
4. Packaging or classloader changes require
   `./gradlew :indexer:verifyPortableDistLayout :indexer:portableDistZip`.
5. Shared contract/storage changes also require the owning
   `:analysis-api:test`, `:analysis-server:test`, or `:index-store:test` suite.
6. Run `./gradlew test` for a cross-module runtime transition, then the
   repository's shell contracts when CLI lifecycle or package layout changed.
