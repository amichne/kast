# Kast Repository Intelligence Report

- Corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Implementation commit: `f61514693b3cfb6558c8aeca61e77e7023429447`
- Benchmark status: `PASS`
- Questions: 42/42

## Architecture

### architecture-01-runtime-call-hubs

- Status: `ANSWERED`
- Question: Which internal Kotlin declarations are high-centrality runtime call hubs, ranked by incoming CALLS only?
- Graph generation: `1582`
- Finding: `connection incoming call hub` — connection has 40 distinct incoming neighbors across 40 compiler occurrences.
- Finding: `ensureProjectReady incoming call hub` — ensureProjectReady has 38 distinct incoming neighbors across 38 compiler occurrences.
- Finding: `backend incoming call hub` — backend has 35 distinct incoming neighbors across 39 compiler occurrences.
- Finding: `dispatchSuccess incoming call hub` — dispatchSuccess has 31 distinct incoming neighbors across 31 compiler occurrences.
- Finding: `waitUntilIndexesAreReady incoming call hub` — waitUntilIndexesAreReady has 29 distinct incoming neighbors across 30 compiler occurrences.
- Finding: `sampleFile incoming call hub` — sampleFile has 29 distinct incoming neighbors across 29 compiler occurrences.
- Finding: `findReferences incoming call hub` — findReferences has 26 distinct incoming neighbors across 39 compiler occurrences.
- Finding: `commonWorkspaceRoot incoming call hub` — commonWorkspaceRoot has 25 distinct incoming neighbors across 25 compiler occurrences.
- Finding: `observe incoming call hub` — observe has 24 distinct incoming neighbors across 24 compiler occurrences.
- Finding: `loadInterningTables incoming call hub` — loadInterningTables has 21 distinct incoming neighbors across 22 compiler occurrences.
- Finding: `issueToken incoming call hub` — issueToken has 20 distinct incoming neighbors across 29 compiler occurrences.
- Finding: `ensureProjectReady incoming call hub` — ensureProjectReady has 20 distinct incoming neighbors across 20 compiler occurrences.
- Finding: `validationBoundary incoming call hub` — validationBoundary has 18 distinct incoming neighbors across 18 compiler occurrences.
- Finding: `diagnostics incoming call hub` — diagnostics has 17 distinct incoming neighbors across 23 compiler occurrences.
- Finding: `workspaceRootFor incoming call hub` — workspaceRootFor has 17 distinct incoming neighbors across 18 compiler occurrences.
- Finding: `takeIfAny incoming call hub` — takeIfAny has 17 distinct incoming neighbors across 17 compiler occurrences.
- Finding: `failureDetails incoming call hub` — failureDetails has 16 distinct incoming neighbors across 18 compiler occurrences.
- Finding: `publish incoming call hub` — publish has 16 distinct incoming neighbors across 16 compiler occurrences.
- Finding: `dispatchRaw incoming call hub` — dispatchRaw has 15 distinct incoming neighbors across 15 compiler occurrences.
- Finding: `append incoming call hub` — append has 15 distinct incoming neighbors across 15 compiler occurrences.
- Finding: `fileUpdate incoming call hub` — fileUpdate has 14 distinct incoming neighbors across 25 compiler occurrences.
- Finding: `findKtFile incoming call hub` — findKtFile has 14 distinct incoming neighbors across 14 compiler occurrences.
- Finding: `dispatchSuccessWithBackend incoming call hub` — dispatchSuccessWithBackend has 13 distinct incoming neighbors across 15 compiler occurrences.
- Finding: `backend incoming call hub` — backend has 13 distinct incoming neighbors across 14 compiler occurrences.
- Finding: `ensureProjectReady incoming call hub` — ensureProjectReady has 13 distinct incoming neighbors across 13 compiler occurrences.
- Finding: `incrementGenerationInTransaction incoming call hub` — incrementGenerationInTransaction has 13 distinct incoming neighbors across 13 compiler occurrences.
- Finding: `timedReadAction incoming call hub` — timedReadAction has 12 distinct incoming neighbors across 20 compiler occurrences.
- Finding: `lookupSymbol incoming call hub` — lookupSymbol has 12 distinct incoming neighbors across 16 compiler occurrences.
- Finding: `isWorkspaceFile incoming call hub` — isWorkspaceFile has 12 distinct incoming neighbors across 15 compiler occurrences.
- Finding: `runIdeaReadAction incoming call hub` — runIdeaReadAction has 12 distinct incoming neighbors across 13 compiler occurrences.
- Finding: `booleanValue incoming call hub` — booleanValue has 11 distinct incoming neighbors across 16 compiler occurrences.
- Finding: `nativeFailure incoming call hub` — nativeFailure has 11 distinct incoming neighbors across 13 compiler occurrences.
- Finding: `span incoming call hub` — span has 10 distinct incoming neighbors across 32 compiler occurrences.
- Finding: `rejected incoming call hub` — rejected has 10 distinct incoming neighbors across 25 compiler occurrences.
- Finding: `placeholderLogFile incoming call hub` — placeholderLogFile has 10 distinct incoming neighbors across 16 compiler occurrences.
- Finding: `requireReadCapability incoming call hub` — requireReadCapability has 10 distinct incoming neighbors across 14 compiler occurrences.
- Finding: `getNullableInt incoming call hub` — getNullableInt has 10 distinct incoming neighbors across 12 compiler occurrences.
- Finding: `scheduleNextExpiryLocked incoming call hub` — scheduleNextExpiryLocked has 10 distinct incoming neighbors across 11 compiler occurrences.
- Finding: `rollbackAndReloadPrefixes incoming call hub` — rollbackAndReloadPrefixes has 10 distinct incoming neighbors across 10 compiler occurrences.
- Finding: `observation incoming call hub` — observation has 9 distinct incoming neighbors across 20 compiler occurrences.
- Finding: `backend incoming call hub` — backend has 9 distinct incoming neighbors across 13 compiler occurrences.
- Finding: `refresh incoming call hub` — refresh has 9 distinct incoming neighbors across 11 compiler occurrences.
- Finding: `disposeRegisteredCapturingFailure incoming call hub` — disposeRegisteredCapturingFailure has 9 distinct incoming neighbors across 10 compiler occurrences.
- Finding: `requireKnownFile incoming call hub` — requireKnownFile has 9 distinct incoming neighbors across 9 compiler occurrences.
- Finding: `ensureProjectReady incoming call hub` — ensureProjectReady has 9 distinct incoming neighbors across 9 compiler occurrences.
- Finding: `registerStateDisposalLocked incoming call hub` — registerStateDisposalLocked has 8 distinct incoming neighbors across 10 compiler occurrences.
- Finding: `toNormalizedRequestPath incoming call hub` — toNormalizedRequestPath has 8 distinct incoming neighbors across 8 compiler occurrences.
- Finding: `normalizedAbsolutePath incoming call hub` — normalizedAbsolutePath has 8 distinct incoming neighbors across 8 compiler occurrences.
- Finding: `awaiter incoming call hub` — awaiter has 8 distinct incoming neighbors across 8 compiler occurrences.
- Finding: `internPathsInTransaction incoming call hub` — internPathsInTransaction has 8 distinct incoming neighbors across 8 compiler occurrences.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:412`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:428`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:564`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:574`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:583`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:592`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:602`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:611`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:632`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:638`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:659`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:669`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:687`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:705`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:713`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:36`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:45`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:86`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:113`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:150`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:185`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:215`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:240`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:302`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:362`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:394`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:472`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:485`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:508`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:513`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:517`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:208`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:210`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:336`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:351`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:369`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:395`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:408`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:412`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:415`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:206`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:214`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:225`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:236`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:245`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:252`
- 369 additional references omitted by the presentation bound

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "architecture",
      "ordering": "metric descending, canonicalKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "ARCHITECTURE",
        "metric": null,
        "projection": "RUNTIME_CALLS"
      },
      "question": "Which internal Kotlin declarations are high-centrality runtime call hubs, ranked by incoming CALLS only?",
      "scope": {
        "direction": "INCOMING",
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": "RUNTIME_CALLS",
        "relations": [],
        "sourceSet": null,
        "sources": []
      }
    }

### architecture-02-cross-boundary-cycles

- Status: `ANSWERED`
- Question: Report strongly connected runtime-call cycles that cross a Gradle module or package boundary.
- Graph generation: `1582`
- Finding: `43-boundary runtime-call cycle` — 43 package or module boundaries form a directed strongly connected component.
- Finding: `21-boundary runtime-call cycle` — 21 package or module boundaries form a directed strongly connected component.
- Finding: `6-boundary runtime-call cycle` — 6 package or module boundaries form a directed strongly connected component.
- Finding: `5-boundary runtime-call cycle` — 5 package or module boundaries form a directed strongly connected component.
- Finding: `5-boundary runtime-call cycle` — 5 package or module boundaries form a directed strongly connected component.
- Finding: `5-boundary runtime-call cycle` — 5 package or module boundaries form a directed strongly connected component.
- Finding: `4-boundary runtime-call cycle` — 4 package or module boundaries form a directed strongly connected component.
- Finding: `2-boundary runtime-call cycle` — 2 package or module boundaries form a directed strongly connected component.
- Finding: `2-boundary runtime-call cycle` — 2 package or module boundaries form a directed strongly connected component.
- Finding: `2-boundary runtime-call cycle` — 2 package or module boundaries form a directed strongly connected component.
- Finding: `2-boundary runtime-call cycle` — 2 package or module boundaries form a directed strongly connected component.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt:42`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/DiagnosticsResult.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/DiagnosticsResult.kt:18`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/DiagnosticsResult.kt:100`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/DiagnosticsResult.kt:174`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipSearchCoverage.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipSearchCoverage.kt:111`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipSearchCoverage.kt:177`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipSearchCoverage.kt:186`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt:205`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt:495`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/IndentedWriter.kt:4`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt:931`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt:1120`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt:1127`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:10`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/DocExampleGenerator.kt`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/DocExampleGenerator.kt:61`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/DocExampleGenerator.kt:443`
- `backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessMain.kt`
- `backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessMain.kt:7`
- `backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessRuntime.kt`
- `backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessRuntime.kt:56`
- `backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessRuntime.kt:62`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastIdeaBackendRuntime.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastIdeaBackendRuntime.kt:142`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ReferenceIndexLookup.kt:40`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/RelationshipContinuationStore.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/RelationshipContinuationStore.kt:84`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/RelationshipContinuationStore.kt:157`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/RelationshipContinuationStore.kt:277`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/RelationshipContinuationStore.kt:359`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt:276`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt:277`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt:35`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt:39`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipOperations.kt:39`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/ModelResolver.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/ModelResolver.kt:53`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/ModelResolver.kt:140`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/ModelResolver.kt:146`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/ParsedQueryTestAdapters.kt`
- 6 additional references omitted by the presentation bound

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "architecture",
      "ordering": "metric descending, canonicalKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "ARCHITECTURE",
        "metric": "SCC",
        "projection": "RUNTIME_CALLS"
      },
      "question": "Report strongly connected runtime-call cycles that cross a Gradle module or package boundary.",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": "SCC",
        "module": null,
        "projection": "RUNTIME_CALLS",
        "relations": [],
        "sourceSet": null,
        "sources": []
      }
    }

### architecture-03-type-boundaries

- Status: `ANSWERED`
- Question: Which module boundaries carry the most explicit Kotlin type dependencies?
- Graph generation: `1582`
- Finding: `.#:backend-idea to .#:analysis-api type boundary` — 531 explicit type-dependency occurrences cross from .#:backend-idea to .#:analysis-api.
- Finding: `.#:analysis-server to .#:analysis-api type boundary` — 347 explicit type-dependency occurrences cross from .#:analysis-server to .#:analysis-api.
- Finding: `.#:backend-idea to .#:backend-shared type boundary` — 56 explicit type-dependency occurrences cross from .#:backend-idea to .#:backend-shared.
- Finding: `.#:backend-shared to .#:analysis-api type boundary` — 50 explicit type-dependency occurrences cross from .#:backend-shared to .#:analysis-api.
- Finding: `.#:index-store to .#:analysis-api type boundary` — 37 explicit type-dependency occurrences cross from .#:index-store to .#:analysis-api.
- Finding: `.#:backend-idea to .#:index-store type boundary` — 36 explicit type-dependency occurrences cross from .#:backend-idea to .#:index-store.
- Finding: `.#:backend-shared to .#:index-store type boundary` — 12 explicit type-dependency occurrences cross from .#:backend-shared to .#:index-store.
- Finding: `.#:backend-idea to .#:analysis-server type boundary` — 9 explicit type-dependency occurrences cross from .#:backend-idea to .#:analysis-server.
- Finding: `.#:backend-headless to .#:analysis-api type boundary` — 2 explicit type-dependency occurrences cross from .#:backend-headless to .#:analysis-api.
- Finding: `.#:backend-headless to .#:backend-idea type boundary` — 1 explicit type-dependency occurrences cross from .#:backend-headless to .#:backend-idea.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerInstanceDescriptor.kt:7`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerLaunchOptions.kt:13`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationCapacity.kt:3`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationTtl.kt:5`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisTransport.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CloseableAnalysisBackend.kt:3`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:13`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:180`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:193`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/DeclarationScope.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/Diagnostic.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/FileHash.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/Location.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/RuntimeLifecycleResponse.kt:9`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/RuntimeOpenProject.kt:87`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/Symbol.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SymbolVisibility.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/TextEdit.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceFileKindDomain.kt:5`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:29`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:182`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:237`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:281`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:325`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastSelectorIdentityResponse.kt:7`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt:10`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt:15`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:10`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:11`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:23`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:26`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/DescriptorStore.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/DescriptorStore.kt:9`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/DescriptorStore.kt:13`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt:527`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt:545`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RunningAnalysisServer.kt:8`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeLifecycleController.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeLifecycleController.kt:5`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeLifecycleController.kt:6`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeProjectOpenController.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeProjectOpenController.kt:7`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeProjectOpenController.kt:8`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeProjectOpenController.kt:19`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt:220`
- 126 additional references omitted by the presentation bound

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "architecture",
      "ordering": "metric descending, canonicalKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "ARCHITECTURE",
        "metric": null,
        "projection": "TYPE_DEPENDENCIES"
      },
      "question": "Which module boundaries carry the most explicit Kotlin type dependencies?",
      "scope": {
        "direction": "OUTGOING",
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": "TYPE_DEPENDENCIES",
        "relations": [],
        "sourceSet": null,
        "sources": []
      }
    }

### architecture-04-communities

- Status: `ANSWERED`
- Question: Name deterministic runtime-call communities and show cohesion, representative exact symbols, and relation composition.
- Graph generation: `1582`
- Finding: `.#:analysis-api / TestState runtime call community` — 72 exact symbols share 289 internal runtime-call edges.
- Finding: `.#:analysis-api / ReferencesQuery runtime call community` — 79 exact symbols share 260 internal runtime-call edges.
- Finding: `.#:analysis-server / AnalysisServerConfig runtime call community` — 91 exact symbols share 224 internal runtime-call edges.
- Finding: `.#:index-store / SqliteSourceIndexStore runtime call community` — 55 exact symbols share 147 internal runtime-call edges.
- Finding: `.#:analysis-api / defaults runtime call community` — 78 exact symbols share 162 internal runtime-call edges.
- Finding: `.#:analysis-api / ApplyEditsQuery runtime call community` — 49 exact symbols share 127 internal runtime-call edges.
- Finding: `.#:backend-idea / KastStructuredTraceFields runtime call community` — 37 exact symbols share 72 internal runtime-call edges.
- Finding: `.#:analysis-api / registerSchemas runtime call community` — 3 exact symbols share 2 internal runtime-call edges.
- Finding: `.#:analysis-api / line runtime call community` — 28 exact symbols share 57 internal runtime-call edges.
- Finding: `.#:analysis-server / dispatchMethod runtime call community` — 13 exact symbols share 16 internal runtime-call edges.
- Finding: `.#:backend-idea / ideaReferenceSearch runtime call community` — 31 exact symbols share 38 internal runtime-call edges.
- Finding: `.#:analysis-api / exists runtime call community` — 33 exact symbols share 78 internal runtime-call edges.
- Finding: `.#:analysis-server / capacity and ttl invalidate handles through the shared policy runtime call community` — 29 exact symbols share 57 internal runtime-call edges.
- Finding: `.#:index-store / typed Gradle and package provenance round-trips and advances generation on every transition runtime call community` — 26 exact symbols share 63 internal runtime-call edges.
- Finding: `.#:backend-idea / query runtime call community` — 22 exact symbols share 50 internal runtime-call edges.
- Finding: `.#:analysis-api / assess runtime call community` — 33 exact symbols share 64 internal runtime-call edges.
- Finding: `.#:analysis-api / query parsed happy paths create typed models runtime call community` — 38 exact symbols share 65 internal runtime-call edges.
- Finding: `.#:index-store / referencesToSymbol runtime call community` — 19 exact symbols share 54 internal runtime-call edges.
- Finding: `.#:index-store / readSemanticGraph runtime call community` — 29 exact symbols share 61 internal runtime-call edges.
- Finding: `.#:analysis-api / workspaceDataDirectory runtime call community` — 24 exact symbols share 53 internal runtime-call edges.
- Finding: `.#:backend-idea / span runtime call community` — 21 exact symbols share 40 internal runtime-call edges.
- Finding: `.#:analysis-api / ConfigurationDefault runtime call community` — 58 exact symbols share 57 internal runtime-call edges.
- Finding: `.#:analysis-api / toJavaPath runtime call community` — 38 exact symbols share 40 internal runtime-call edges.
- Finding: `.#:analysis-server / limitedRelationshipEvidence runtime call community` — 15 exact symbols share 14 internal runtime-call edges.
- Finding: `.#:backend-shared / span runtime call community` — 18 exact symbols share 44 internal runtime-call edges.
- Finding: `.#:backend-headless / observation runtime call community` — 20 exact symbols share 44 internal runtime-call edges.
- Finding: `.#:backend-headless / modelReadiness runtime call community` — 20 exact symbols share 44 internal runtime-call edges.
- Finding: `.#:backend-idea / injected project model preserves scripts exact owners and workspace containment runtime call community` — 12 exact symbols share 17 internal runtime-call edges.
- Finding: `.#:backend-idea / relationship queries reassess coverage in the final commit epoch runtime call community` — 21 exact symbols share 45 internal runtime-call edges.
- Finding: `.#:backend-idea / backend runtime call community` — 16 exact symbols share 40 internal runtime-call edges.
- Finding: `.#:backend-idea / git runtime call community` — 12 exact symbols share 16 internal runtime-call edges.
- Finding: `.#:analysis-api / sha256 runtime call community` — 22 exact symbols share 39 internal runtime-call edges.
- Finding: `.#:analysis-api / manualUnionSchema runtime call community` — 7 exact symbols share 9 internal runtime-call edges.
- Finding: `.#:index-store / replaceReferencesFromFiles runtime call community` — 18 exact symbols share 34 internal runtime-call edges.
- Finding: `.#:analysis-api / writePaths runtime call community` — 7 exact symbols share 13 internal runtime-call edges.
- Finding: `.#:backend-idea / extractSemanticGraphFile runtime call community` — 17 exact symbols share 19 internal runtime-call edges.
- Finding: `.#:backend-idea / handles fail closed for family query and generation mismatch runtime call community` — 11 exact symbols share 19 internal runtime-call edges.
- Finding: `.#:analysis-api / expectEquals runtime call community` — 9 exact symbols share 14 internal runtime-call edges.
- Finding: `.#:backend-idea / KastDiagnosticsSnapshot runtime call community` — 20 exact symbols share 34 internal runtime-call edges.
- Finding: `.#:backend-idea / clean target preserves immutable base facts in isolated worktree databases and blob shards runtime call community` — 15 exact symbols share 20 internal runtime-call edges.
- Finding: `.#:analysis-api / workspaceFiles runtime call community` — 12 exact symbols share 19 internal runtime-call edges.
- Finding: `.#:backend-idea / load runtime call community` — 9 exact symbols share 10 internal runtime-call edges.
- Finding: `.#:analysis-server / request runtime call community` — 6 exact symbols share 6 internal runtime-call edges.
- Finding: `.#:backend-idea / rejected runtime call community` — 14 exact symbols share 20 internal runtime-call edges.
- Finding: `.#:backend-shared / invalid model accumulates typed violations runtime call community` — 12 exact symbols share 26 internal runtime-call edges.
- Finding: `.#:analysis-server / lookupSymbol runtime call community` — 12 exact symbols share 27 internal runtime-call edges.
- Finding: `.#:analysis-api / ValidationException runtime call community` — 10 exact symbols share 10 internal runtime-call edges.
- Finding: `.#:analysis-api / diagnostic continuations are single use and query bound runtime call community` — 10 exact symbols share 12 internal runtime-call edges.
- Finding: `.#:analysis-api / merge runtime call community` — 16 exact symbols share 23 internal runtime-call edges.
- Finding: `.#:backend-idea / resolved identity and P1-P3 extraction are source backed runtime call community` — 11 exact symbols share 10 internal runtime-call edges.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/DescriptorRegistry.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/DescriptorRegistry.kt:49`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/GitRemoteParser.kt:12`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/GitRemoteParser.kt:80`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:127`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:141`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:218`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:481`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:826`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:950`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:983`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1006`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1048`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1103`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerLaunchOptions.kt:85`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:13`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:20`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:47`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:53`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:55`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:62`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:66`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:73`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:147`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:150`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:9`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:41`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:44`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:47`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:50`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:80`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:97`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:124`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt:12`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheEnabled.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheEnabled.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheSourceIndexSaveDelayMillis.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheSourceIndexSaveDelayMillis.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheWriteDelayMillis.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheWriteDelayMillis.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CliBinaryPath.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CliBinaryPath.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexHooksEnabled.kt`
- 585 additional references omitted by the presentation bound

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "architecture",
      "ordering": "metric descending, canonicalKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "ARCHITECTURE",
        "metric": "COMMUNITIES",
        "projection": "RUNTIME_CALLS"
      },
      "question": "Name deterministic runtime-call communities and show cohesion, representative exact symbols, and relation composition.",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": "COMMUNITIES",
        "module": null,
        "projection": "RUNTIME_CALLS",
        "relations": [],
        "sourceSet": null,
        "sources": []
      }
    }

### architecture-05-thin-bridges

- Status: `ANSWERED`
- Question: Find thin exact-symbol bridges between otherwise separated Kotlin subsystems under REFERENCES.
- Graph generation: `1582`
- Finding: `.#:analysis-server to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:backend-idea reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:backend-idea reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:backend-idea to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `.#:analysis-api to .#:analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:238`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:242`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:246`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:842`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:846`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:852`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:859`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:865`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:871`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:877`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:887`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:892`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:898`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:902`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:906`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:913`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:922`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:927`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:931`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:935`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:946`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:969`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:973`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:977`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:994`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1000`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1006`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1013`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1019`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1029`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1034`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1040`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1044`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1048`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1055`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1064`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1071`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1075`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1081`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1088`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1092`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1103`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerInstanceDescriptor.kt:7`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:97`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationLeaseResult.kt:3`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationTransition.kt:3`
- 46 additional references omitted by the presentation bound

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "architecture",
      "ordering": "metric descending, canonicalKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "ARCHITECTURE",
        "metric": "BRIDGES",
        "projection": "SYMBOL_REFERENCES"
      },
      "question": "Find thin exact-symbol bridges between otherwise separated Kotlin subsystems under REFERENCES.",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": "BRIDGES",
        "module": null,
        "projection": "SYMBOL_REFERENCES",
        "relations": [],
        "sourceSet": null,
        "sources": []
      }
    }

### architecture-06-public-api-consumers

- Status: `ANSWERED`
- Question: Which public Kotlin APIs are consumed by otherwise unrelated components in the type-dependency projection?
- Graph generation: `1582`
- Finding: `ConfigurationDefault cross-component public API` — ConfigurationDefault is consumed from 52 unrelated package or module boundaries.
- Finding: `KastExactSymbolSelector cross-component public API` — KastExactSymbolSelector is consumed from 51 unrelated package or module boundaries.
- Finding: `NormalizedPath cross-component public API` — NormalizedPath is consumed from 43 unrelated package or module boundaries.
- Finding: `Symbol cross-component public API` — Symbol is consumed from 35 unrelated package or module boundaries.
- Finding: `SymbolIdentity cross-component public API` — SymbolIdentity is consumed from 25 unrelated package or module boundaries.
- Finding: `RelationshipResultEvidence cross-component public API` — RelationshipResultEvidence is consumed from 24 unrelated package or module boundaries.
- Finding: `SourceSpan cross-component public API` — SourceSpan is consumed from 21 unrelated package or module boundaries.
- Finding: `Limited cross-component public API` — Limited is consumed from 20 unrelated package or module boundaries.
- Finding: `Location cross-component public API` — Location is consumed from 19 unrelated package or module boundaries.
- Finding: `RelationshipSearchLimitation cross-component public API` — RelationshipSearchLimitation is consumed from 18 unrelated package or module boundaries.
- Finding: `PositiveInt cross-component public API` — PositiveInt is consumed from 17 unrelated package or module boundaries.
- Finding: `KastConfig cross-component public API` — KastConfig is consumed from 16 unrelated package or module boundaries.
- Finding: `WrapperNamedSymbolKind cross-component public API` — WrapperNamedSymbolKind is consumed from 16 unrelated package or module boundaries.
- Finding: `WorkspaceFileKindDomain cross-component public API` — WorkspaceFileKindDomain is consumed from 16 unrelated package or module boundaries.
- Finding: `PredicateId cross-component public API` — PredicateId is consumed from 16 unrelated package or module boundaries.
- Finding: `AnalysisBackend cross-component public API` — AnalysisBackend is consumed from 15 unrelated package or module boundaries.
- Finding: `TextEdit cross-component public API` — TextEdit is consumed from 15 unrelated package or module boundaries.
- Finding: `CallableKey cross-component public API` — CallableKey is consumed from 15 unrelated package or module boundaries.
- Finding: `FileHash cross-component public API` — FileHash is consumed from 15 unrelated package or module boundaries.
- Finding: `Diagnostic cross-component public API` — Diagnostic is consumed from 14 unrelated package or module boundaries.
- Finding: `NonNegativeInt cross-component public API` — NonNegativeInt is consumed from 14 unrelated package or module boundaries.
- Finding: `SymbolKind cross-component public API` — SymbolKind is consumed from 14 unrelated package or module boundaries.
- Finding: `ApplyEditsResult cross-component public API` — ApplyEditsResult is consumed from 14 unrelated package or module boundaries.
- Finding: `ParsedApplyEditsQuery cross-component public API` — ParsedApplyEditsQuery is consumed from 14 unrelated package or module boundaries.
- Finding: `NonBlankString cross-component public API` — NonBlankString is consumed from 13 unrelated package or module boundaries.
- Finding: `RelationshipSearchCoverage cross-component public API` — RelationshipSearchCoverage is consumed from 13 unrelated package or module boundaries.
- Finding: `TrackedValueId cross-component public API` — TrackedValueId is consumed from 13 unrelated package or module boundaries.
- Finding: `ResultCardinality cross-component public API` — ResultCardinality is consumed from 13 unrelated package or module boundaries.
- Finding: `ContinuationAccessFailure cross-component public API` — ContinuationAccessFailure is consumed from 13 unrelated package or module boundaries.
- Finding: `FilePosition cross-component public API` — FilePosition is consumed from 13 unrelated package or module boundaries.
- Finding: `SelectorHandleAuthority cross-component public API` — SelectorHandleAuthority is consumed from 12 unrelated package or module boundaries.
- Finding: `ParsedFilePosition cross-component public API` — ParsedFilePosition is consumed from 12 unrelated package or module boundaries.
- Finding: `DiagnosticsResult cross-component public API` — DiagnosticsResult is consumed from 12 unrelated package or module boundaries.
- Finding: `BoundaryId cross-component public API` — BoundaryId is consumed from 12 unrelated package or module boundaries.
- Finding: `BackendCapabilities cross-component public API` — BackendCapabilities is consumed from 12 unrelated package or module boundaries.
- Finding: `ApiErrorResponse cross-component public API` — ApiErrorResponse is consumed from 12 unrelated package or module boundaries.
- Finding: `ParsedDiagnosticsQuery cross-component public API` — ParsedDiagnosticsQuery is consumed from 12 unrelated package or module boundaries.
- Finding: `ParsedReferencesQuery cross-component public API` — ParsedReferencesQuery is consumed from 11 unrelated package or module boundaries.
- Finding: `ServerLimits cross-component public API` — ServerLimits is consumed from 10 unrelated package or module boundaries.
- Finding: `ParsedSymbolQuery cross-component public API` — ParsedSymbolQuery is consumed from 10 unrelated package or module boundaries.
- Finding: `WorkspaceFilesPublicContinuationIdentity cross-component public API` — WorkspaceFilesPublicContinuationIdentity is consumed from 10 unrelated package or module boundaries.
- Finding: `WorkspaceIdentity cross-component public API` — WorkspaceIdentity is consumed from 10 unrelated package or module boundaries.
- Finding: `CliImplementationVersion cross-component public API` — CliImplementationVersion is consumed from 10 unrelated package or module boundaries.
- Finding: `GitObjectId cross-component public API` — GitObjectId is consumed from 10 unrelated package or module boundaries.
- Finding: `ParsedRenameQuery cross-component public API` — ParsedRenameQuery is consumed from 10 unrelated package or module boundaries.
- Finding: `SqliteSourceIndexStore cross-component public API` — SqliteSourceIndexStore is consumed from 10 unrelated package or module boundaries.
- Finding: `SemanticGraphSourcePath cross-component public API` — SemanticGraphSourcePath is consumed from 9 unrelated package or module boundaries.
- Finding: `PageInfo cross-component public API` — PageInfo is consumed from 9 unrelated package or module boundaries.
- Finding: `FileAnalysisStatus cross-component public API` — FileAnalysisStatus is consumed from 9 unrelated package or module boundaries.
- Finding: `ReferencesResult cross-component public API` — ReferencesResult is consumed from 9 unrelated package or module boundaries.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:31`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:127`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:141`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:149`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:178`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:950`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:969`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerLaunchOptions.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerLaunchOptions.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerLaunchOptions.kt:48`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt:57`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:20`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:21`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:25`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:26`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:27`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:30`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:31`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:34`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:35`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:97`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheEnabled.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheEnabled.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheSourceIndexSaveDelayMillis.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheSourceIndexSaveDelayMillis.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheWriteDelayMillis.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CacheWriteDelayMillis.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CliBinaryPath.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CliBinaryPath.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexHooksEnabled.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexHooksEnabled.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexPostToolUseEnabled.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexPostToolUseEnabled.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexSessionStartEnabled.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/CodexSessionStartEnabled.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefault.kt:3`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationField.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationField.kt:6`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/GradleToolingApiTimeoutMillis.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/GradleToolingApiTimeoutMillis.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/HeadlessBackendEnabled.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/HeadlessBackendEnabled.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationAccessFailure.kt:3`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationConsumeResult.kt`
- 660 additional references omitted by the presentation bound

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "architecture",
      "ordering": "metric descending, canonicalKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "ARCHITECTURE",
        "metric": "PUBLIC_API_CONSUMERS",
        "projection": "TYPE_DEPENDENCIES"
      },
      "question": "Which public Kotlin APIs are consumed by otherwise unrelated components in the type-dependency projection?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": "PUBLIC_API_CONSUMERS",
        "module": null,
        "projection": "TYPE_DEPENDENCIES",
        "relations": [],
        "sourceSet": null,
        "sources": []
      }
    }

## Repository context

### context-01-compiler-evidence-doc

- Status: `ANSWERED`
- Question: Which document explains why SemanticGraphRelation records both endpoints and an exact source occurrence?
- Graph generation: `1582`
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphRelation` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `SemanticGraphRelation` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `SemanticGraphRelation` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `SemanticGraphRelation` (extracted)
- Relation: `.agents/adr/0031-cli-install-and-data-authority.md:38` DOCUMENTS `SemanticGraphRelation` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `SemanticGraphRelation` (extracted)

Source references:

- `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42`
- `.agents/adr/0026-proof-carrying-relationship-coverage.md:32`
- `.agents/adr/0031-cli-install-and-data-authority.md:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:281`
- `docs/explanation/architecture.md:8`
- `docs/explanation/compiler-evidence.md:7`
- `docs/how-to/explore-kotlin-code.md:9`

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "context_relationship",
      "ordering": "source priority, score descending, sourcePath ascending, targetKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [
          "markdown"
        ],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "CONTEXT_RELATIONSHIP",
        "metric": null,
        "projection": null
      },
      "question": "Which document explains why SemanticGraphRelation records both endpoints and an exact source occurrence?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": null,
        "relations": [],
        "sourceSet": null,
        "sources": [
          "markdown"
        ]
      }
    }

### context-02-gradle-owner

- Status: `ANSWERED`
- Question: Which Gradle project and source set own semanticGraphOperation?
- Graph generation: `1582`
- Relation: `backend-idea/build.gradle.kts:1` CONFIGURES_MODULE `semanticGraphOperation` (derived)

Source references:

- `backend-idea/build.gradle.kts:1`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt:105`

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "context_relationship",
      "ordering": "source priority, score descending, sourcePath ascending, targetKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [
          "gradle"
        ],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "CONTEXT_RELATIONSHIP",
        "metric": null,
        "projection": null
      },
      "question": "Which Gradle project and source set own semanticGraphOperation?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": null,
        "relations": [],
        "sourceSet": null,
        "sources": [
          "gradle"
        ]
      }
    }

### context-03-semantic-graph-schema

- Status: `ANSWERED`
- Question: Which checked-in request schema exposes the raw semantic graph operation implemented by semanticGraphOperation?
- Graph generation: `1582`
- Relation: `cli-rs/protocol/source/requests/raw/semantic-graph/request.schema.json:26` IMPLEMENTS_PROTOCOL `semanticGraphOperation` (derived)

Source references:

- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt:105`
- `cli-rs/protocol/source/requests/raw/semantic-graph/request.schema.json:26`

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "context_relationship",
      "ordering": "source priority, score descending, sourcePath ascending, targetKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [
          "schema"
        ],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "CONTEXT_RELATIONSHIP",
        "metric": null,
        "projection": null
      },
      "question": "Which checked-in request schema exposes the raw semantic graph operation implemented by semanticGraphOperation?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": null,
        "relations": [],
        "sourceSet": null,
        "sources": [
          "schema"
        ]
      }
    }

### context-04-relationship-coverage-adr

- Status: `ANSWERED`
- Question: Which ADR establishes proof-carrying relationship coverage, and which exact Kotlin model carries that evidence?
- Graph generation: `1582`
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `CompleteRelationshipCoverageSerializer` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `ResumableRelationshipCoverageSerializer` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `LimitedRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphCoverage` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphTypeEdge` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphRelation` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphDiagnosticEvidence` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphFileCoverage` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphRelationKind` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphRelationContext` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `ExactRelationshipCardinalitySerializer` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `KnownMinimumRelationshipCardinalitySerializer` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `RelationshipCoverageStatus` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `RelationshipResultEvidence` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `RelationshipSearchCoverage` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:34` DOCUMENTS `CompleteRelationshipCoverageAdmission` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `CompleteRelationshipCoverageSerializer` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `ResumableRelationshipCoverageSerializer` (extracted)
- Relation: `.agents/adr/0026-proof-carrying-relationship-coverage.md:32` DOCUMENTS `LimitedRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `RelationshipCoverageStatus` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `RelationshipResultEvidence` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `RelationshipSearchCoverage` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `CompleteRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `ResumableRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `LimitedRelationshipCoverageSerializer` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `RelationshipCoverageStatus` (extracted)
- Relation: `docs/how-to/explore-kotlin-code.md:9` DOCUMENTS `RelationshipSearchCoverage` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `RelationshipCoverageStatus` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `RelationshipResultEvidence` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `RelationshipSearchCoverage` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:44` DOCUMENTS `CompleteRelationshipCoverageAdmission` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `CompleteRelationshipCoverageSerializer` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `ResumableRelationshipCoverageSerializer` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42` DOCUMENTS `LimitedRelationshipCoverageSerializer` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:44` DOCUMENTS `IdeaRelationshipCoverageAuthority` (extracted)
- Relation: `.agents/adr/0025-backend-bound-opaque-selector-handles.md:44` DOCUMENTS `RelationshipCoverageAuthority` (extracted)
- Relation: `docs/tutorials/first-compiler-backed-task.md:7` DOCUMENTS `IdeaRelationshipCoverageAuthority` (extracted)
- Relation: `docs/tutorials/first-compiler-backed-task.md:8` DOCUMENTS `RelationshipCoverageTestInputs` (extracted)
- Relation: `docs/tutorials/first-compiler-backed-task.md:7` DOCUMENTS `RelationshipCoverageAuthority` (extracted)
- Relation: `docs/tutorials/first-compiler-backed-task.md:7` DOCUMENTS `CompleteRelationshipCoverageAdmission` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `RelationshipCoverageStatus` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `RelationshipResultEvidence` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `RelationshipSearchCoverage` (extracted)
- Relation: `docs/explanation/architecture.md:9` DOCUMENTS `CompleteRelationshipCoverageAdmission` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `CompleteRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `ResumableRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/architecture.md:8` DOCUMENTS `LimitedRelationshipCoverageSerializer` (extracted)
- Relation: `docs/explanation/architecture.md:9` DOCUMENTS `IdeaRelationshipCoverageAuthority` (extracted)
- Relation: `docs/explanation/architecture.md:9` DOCUMENTS `RelationshipCoverageAuthority` (extracted)
- Relation: `docs/how-to/troubleshoot.md:9` DOCUMENTS `IdeaRelationshipCoverageAuthority` (extracted)

Source references:

- `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42`
- `.agents/adr/0025-backend-bound-opaque-selector-handles.md:44`
- `.agents/adr/0026-proof-carrying-relationship-coverage.md:32`
- `.agents/adr/0026-proof-carrying-relationship-coverage.md:34`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipCoverageStatus.kt:5`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:11`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:88`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:104`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:120`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:136`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:152`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipSearchCoverage.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:174`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:201`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:216`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:281`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:307`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:325`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:337`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaRelationshipCoverageAuthority.kt:14`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/RelationshipCoverageAuthority.kt:6`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt:249`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:2052`
- `docs/explanation/architecture.md:8`
- `docs/explanation/architecture.md:9`
- `docs/explanation/compiler-evidence.md:7`
- `docs/how-to/explore-kotlin-code.md:9`
- `docs/how-to/troubleshoot.md:9`
- `docs/tutorials/first-compiler-backed-task.md:7`
- `docs/tutorials/first-compiler-backed-task.md:8`

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "context_relationship",
      "ordering": "source priority, score descending, sourcePath ascending, targetKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [
          "markdown"
        ],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "CONTEXT_RELATIONSHIP",
        "metric": null,
        "projection": null
      },
      "question": "Which ADR establishes proof-carrying relationship coverage, and which exact Kotlin model carries that evidence?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": null,
        "relations": [],
        "sourceSet": null,
        "sources": [
          "markdown"
        ]
      }
    }

### context-05-rust-consumes-schema

- Status: `ANSWERED`
- Question: Which Rust native graph surface consumes the semantic edge occurrence schema owned by SqliteSourceIndexStore?
- Graph generation: `1582`
- Relation: `cli-rs/src/agent/native_graph.rs:512` CONSUMES_SCHEMA `SqliteSourceIndexStore` (derived)

Source references:

- `cli-rs/src/agent/native_graph.rs:512`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt:65`

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "context_relationship",
      "ordering": "source priority, score descending, sourcePath ascending, targetKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [
          "rust",
          "schema"
        ],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "CONTEXT_RELATIONSHIP",
        "metric": null,
        "projection": null
      },
      "question": "Which Rust native graph surface consumes the semantic edge occurrence schema owned by SqliteSourceIndexStore?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": null,
        "relations": [],
        "sourceSet": null,
        "sources": [
          "rust",
          "schema"
        ]
      }
    }

### context-06-workflow-to-implementation

- Status: `ANSWERED`
- Question: Which CI workflow test surface reaches the backend-idea implementation of semanticGraphOperation?
- Graph generation: `1582`
- Relation: `.github/workflows/ci.yml:959` CONFIGURES_MODULE `semanticGraphOperation` (derived)
- Relation: `.github/workflows/release.yml:651` CONFIGURES_MODULE `semanticGraphOperation` (derived)

Source references:

- `.github/workflows/ci.yml:959`
- `.github/workflows/release.yml:651`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt:105`

Reproducible query descriptor:

    {
      "bounds": {
        "depth": 6,
        "evidence": 5,
        "results": 50
      },
      "graphGeneration": 1582,
      "intent": "context_relationship",
      "ordering": "source priority, score descending, sourcePath ascending, targetKey ascending",
      "queryPlan": {
        "candidateLookup": "deterministic compiler-symbol ranking",
        "contextSources": [
          "workflow"
        ],
        "discovery": "LEXICAL",
        "execution": "generation-pinned source-index",
        "intent": "CONTEXT_RELATIONSHIP",
        "metric": null,
        "projection": null
      },
      "question": "Which CI workflow test surface reaches the backend-idea implementation of semanticGraphOperation?",
      "scope": {
        "direction": null,
        "language": "kotlin",
        "maxDepth": null,
        "metric": null,
        "module": null,
        "projection": null,
        "relations": [],
        "sourceSet": null,
        "sources": [
          "workflow"
        ]
      }
    }
