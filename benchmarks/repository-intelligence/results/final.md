# Kast Repository Intelligence Report

- Corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Implementation commit: `5ac947c059f25465721a721e447c38246c6006ac`
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

Source references:

- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:225`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:245`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:263`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:279`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:943`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:1186`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:1213`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:1236`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:1483`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:1610`
- `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt:2370`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaBackendPerformanceTest.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaBackendPerformanceTest.kt:246`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaEditApplicationTest.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaEditApplicationTest.kt:96`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaReferenceIndexEnvironmentTest.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaReferenceIndexEnvironmentTest.kt:60`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaReferenceIndexEnvironmentTest.kt:80`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaReferenceIndexEnvironmentTest.kt:104`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaTestIndexing.kt:6`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastDiagnosticsCompletenessTest.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastDiagnosticsCompletenessTest.kt:97`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastDiagnosticsCompletenessTest.kt:156`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastIdeaBackendRuntimeTest.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastIdeaBackendRuntimeTest.kt:41`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:157`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:185`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:233`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:252`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:261`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:272`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:295`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:317`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:337`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:356`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:372`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:377`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:393`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:415`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:435`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:453`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:475`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:495`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt:571`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt:111`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt:238`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt:922`
- 8 additional references omitted by the presentation bound

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
        "fixture": null,
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

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt:42`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastIdeaBackendRuntime.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastIdeaBackendRuntime.kt:142`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ReferenceIndexLookup.kt:40`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt:276`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt:277`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt:35`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipCoverage.kt:39`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/relationships/RelationshipOperations.kt:39`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/ParsedQueryTestAdapters.kt`
- `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/ParsedQueryTestAdapters.kt:25`
- `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ArgumentIndex.kt`
- `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ArgumentIndex.kt:3`
- `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ArgumentIndex.kt:8`
- `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModelTest.kt`
- `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModelTest.kt:134`

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
        "fixture": null,
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
- Finding: `backend-idea to analysis-api type boundary` — 531 explicit type-dependency occurrences cross from backend-idea to analysis-api.
- Finding: `analysis-server to analysis-api type boundary` — 347 explicit type-dependency occurrences cross from analysis-server to analysis-api.
- Finding: `backend-idea to backend-shared type boundary` — 56 explicit type-dependency occurrences cross from backend-idea to backend-shared.
- Finding: `backend-shared to analysis-api type boundary` — 50 explicit type-dependency occurrences cross from backend-shared to analysis-api.
- Finding: `index-store to analysis-api type boundary` — 37 explicit type-dependency occurrences cross from index-store to analysis-api.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerInstanceDescriptor.kt:7`
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
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:11`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:23`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt:26`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/DescriptorStore.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/DescriptorStore.kt:9`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/DescriptorStore.kt:13`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt:527`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt:545`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeLifecycleController.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeLifecycleController.kt:6`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeProjectOpenController.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RuntimeProjectOpenController.kt:8`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt:220`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaBackendTelemetry.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaBackendTelemetry.kt:213`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaCallEdgeResolver.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaCallEdgeResolver.kt:31`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaCallEdgeResolver.kt:34`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaCallEdgeResolver.kt:80`
- 65 additional references omitted by the presentation bound

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
        "fixture": null,
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
- Finding: `analysis-api / TestState runtime call community` — 72 exact symbols share 289 internal runtime-call edges.
- Finding: `analysis-api / ReferencesQuery runtime call community` — 79 exact symbols share 260 internal runtime-call edges.
- Finding: `analysis-server / AnalysisServerConfig runtime call community` — 91 exact symbols share 224 internal runtime-call edges.
- Finding: `index-store / SqliteSourceIndexStore runtime call community` — 55 exact symbols share 147 internal runtime-call edges.
- Finding: `analysis-api / defaults runtime call community` — 78 exact symbols share 162 internal runtime-call edges.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerLaunchOptions.kt:85`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:14`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:15`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:18`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:19`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:20`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:21`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt:22`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationCapacity.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationCapacity.kt:3`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationCapacity.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationConsumeResult.kt:13`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationLeaseResult.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationTtl.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationTtl.kt:5`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationTtl.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:124`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:137`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:185`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt:561`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/FilePosition.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/ServerLimits.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/ServerLimits.kt:31`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/ServerLimits.kt:34`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ReferencesQuery.kt:12`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/selector/SelectorHandleAuthority.kt:7`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt:214`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedReferencesQuery.kt:6`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/FakeAnalysisBackendContinuationTest.kt`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/FakeAnalysisBackendContinuationTest.kt:47`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt:210`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt:227`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt:240`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ServerLimitsTest.kt`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ServerLimitsTest.kt:12`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/continuation/ContinuationDomainTypesTest.kt`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/continuation/ContinuationDomainTypesTest.kt:8`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt:1293`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt:1472`
- `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt:1488`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt:15`
- 28 additional references omitted by the presentation bound

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
        "fixture": null,
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
- Finding: `analysis-server to analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `analysis-api to analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `analysis-api to analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `analysis-api to analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.
- Finding: `analysis-api to analysis-api reference bridge` — 1 exact reference edges connect otherwise separate deterministic communities.

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:238`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:852`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:859`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:865`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:969`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1000`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1006`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt:1013`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ServerInstanceDescriptor.kt:7`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt:15`

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
        "fixture": null,
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

Source references:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:20`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:21`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:26`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:27`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:30`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:31`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:34`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt:35`
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
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CallNode.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CallNode.kt:12`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:13`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:27`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:32`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:47`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:58`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/CoreTypes.kt:77`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/OutlineSymbol.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/OutlineSymbol.kt:12`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/Symbol.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SymbolIdentity.kt:12`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ReferencesQuery.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ReferencesQuery.kt:27`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/CallRelation.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/CallRelation.kt:10`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt:13`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ImplementationRelation.kt`
- 47 additional references omitted by the presentation bound

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
        "fixture": null,
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
        "fixture": null,
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
        "fixture": null,
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
        "fixture": null,
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
- Relation: `docs/explanation/compiler-evidence.md:7` DOCUMENTS `SemanticGraphTypeFact` (extracted)
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
- Relation: `.agents/adr/0031-cli-install-and-data-authority.md:38` DOCUMENTS `RelationshipCoverageStatus` (extracted)
- Relation: `.agents/adr/0031-cli-install-and-data-authority.md:38` DOCUMENTS `RelationshipResultEvidence` (extracted)

Source references:

- `.agents/adr/0025-backend-bound-opaque-selector-handles.md:42`
- `.agents/adr/0025-backend-bound-opaque-selector-handles.md:44`
- `.agents/adr/0026-proof-carrying-relationship-coverage.md:32`
- `.agents/adr/0026-proof-carrying-relationship-coverage.md:34`
- `.agents/adr/0031-cli-install-and-data-authority.md:38`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipCoverageStatus.kt:5`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:11`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:88`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:104`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:120`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:136`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt:152`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipSearchCoverage.kt:8`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:174`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt:182`
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
        "fixture": null,
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
        "fixture": null,
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
        "fixture": null,
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
