package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorOperationFamily
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeLifecycleAction
import io.github.amichne.kast.api.contract.RuntimeLifecycleResponse
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequest
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequestId
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResponse
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResult
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import io.github.amichne.kast.api.validation.ParsedDiagnosticsQuery
import io.github.amichne.kast.api.validation.ParsedReferencesQuery
import io.github.amichne.kast.api.validation.ParsedSymbolQuery
import io.github.amichne.kast.api.validation.ParsedWorkspaceSymbolQuery
import io.github.amichne.kast.api.validation.ParsedImportOptimizeQuery
import io.github.amichne.kast.api.validation.ParsedRefreshQuery
import io.github.amichne.kast.api.validation.ParsedRenameQuery
import io.github.amichne.kast.testing.AnalysisBackendContractFixture
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.readText

class AnalysisDispatcherTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `runtime status dispatches without HTTP`() {
        val result = dispatchSuccess<RuntimeStatusResponse>("runtime/status")

        assertEquals(RuntimeState.READY, result.state)
        assertEquals("fake", result.backendName)
    }

    @Test
    fun `capabilities dispatches without HTTP`() {
        val result = dispatchSuccess<BackendCapabilities>("capabilities")

        assertTrue(result.readCapabilities.contains(ReadCapability.RESOLVE_SYMBOL))
        assertEquals("fake", result.backendName)
    }

    @Test
    fun `runtime restart schedules lifecycle action after response`() {
        val actions = mutableListOf<RuntimeLifecycleAction>()
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            lifecycleController = RuntimeLifecycleController { action ->
                { actions += action }
            },
        )

        val raw = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/restart"))
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(
            RuntimeLifecycleResponse.serializer(),
            response.result,
        )

        assertEquals(RuntimeLifecycleAction.RESTART, result.action)
        assertTrue(result.accepted)
        assertTrue(actions.isEmpty(), "Lifecycle action must wait until the transport flushes the response")

        assertTrue(dispatcher.runAfterResponseActions())
        assertEquals(listOf(RuntimeLifecycleAction.RESTART), actions)
        assertFalse(dispatcher.runAfterResponseActions())
    }

    @Test
    fun `runtime open project forwards the authenticated exact-root request`() {
        var received: RuntimeOpenProjectRequest? = null
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            projectOpenController = RuntimeProjectOpenController { request ->
                received = request
                RuntimeOpenProjectResponse(RuntimeOpenProjectResult.OPENED_NEW_PROJECT)
            },
        )
        val request = RuntimeOpenProjectRequest(
            canonicalRoot = RuntimeOpenProjectRoot.parse(tempDir.toRealPath().toString()),
            requestId = RuntimeOpenProjectRequestId.parse("a7370b30-7ca5-4fa5-93c0-e59d30aa6157"),
        )

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "runtime/open-project",
                    params = json.encodeToJsonElement(RuntimeOpenProjectRequest.serializer(), request),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(RuntimeOpenProjectResponse.serializer(), response.result)

        assertEquals(request, received)
        assertEquals(RuntimeOpenProjectResult.OPENED_NEW_PROJECT, result.result)
    }

    @Test
    fun `dispatcher bytecode avoids kotlin Duration ABI coupling`() {
        val classFileText = classFileText(RpcAnalysisDispatcher::class.java)

        assertFalse(classFileText.contains("kotlin/time/Duration"))
        assertFalse(classFileText.contains("fromRawValue-UwyO8pc"))
    }

    @Test
    fun `symbol resolve dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<SymbolResult>(
            method = "raw/resolve",
            params = json.encodeToJsonElement(
                SymbolQuery.serializer(),
                SymbolQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    includeDocumentation = true,
                ),
            ),
        )

        assertEquals("sample.greet", result.symbol.fqName)
        assertTrue(result.symbol.documentation != null)
        assertTrue(result.symbol.parameters != null)
    }

    @Test
    fun `symbol resolve with includeDeclarationScope passes through`() {
        val file = sampleFile()

        val result = dispatchSuccess<SymbolResult>(
            method = "raw/resolve",
            params = json.encodeToJsonElement(
                SymbolQuery.serializer(),
                SymbolQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    includeDeclarationScope = true,
                ),
            ),
        )

        assertEquals("sample.greet", result.symbol.fqName)
    }

    @Test
    fun `file outline includes declarationScope on symbols`() {
        val file = sampleFile()

        val result = dispatchSuccess<FileOutlineResult>(
            method = "raw/file-outline",
            params = json.encodeToJsonElement(
                FileOutlineQuery.serializer(),
                FileOutlineQuery(filePath = file.toString()),
            ),
        )

        assertTrue(result.symbols.isNotEmpty())
        assertEquals("sample.greet", result.symbols.first().symbol.fqName)
    }

    @Test
    fun `symbol resolve dispatches named-symbol orchestration`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        assertEquals("sample.greet", success.symbol.fqName)
        assertEquals(file.toString(), success.filePath)
        assertEquals(true, success.ok)
    }

    @Test
    fun `selector handle reuses exact reference subject without re-resolution`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        var resolveCalls = 0
        var referenceCalls = 0
        val backend = object : AnalysisBackend by delegate {
            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolveCalls += 1
                return delegate.resolveSymbol(query)
            }

            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult {
                referenceCalls += 1
                return delegate.findReferences(query)
            }
        }
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend,
            config = AnalysisServerConfig(),
        )
        val resolvedRaw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/resolve",
                    params = json.encodeToJsonElement(
                        KastResolveRequest.serializer(),
                        KastResolveRequest(
                            workspaceRoot = tempDir.toString(),
                            symbol = "greet",
                            fileHint = sampleFile().toString(),
                        ),
                    ),
                ),
            )
        }
        val resolvedResponse = json.decodeFromString(JsonRpcSuccessResponse.serializer(), resolvedRaw)
        val resolvedResult = resolvedResponse.result as JsonObject
        val selectorHandle = assertInstanceOf(
            JsonPrimitive::class.java,
            resolvedResult["selectorHandle"],
        ).content
        assertTrue(selectorHandle.startsWith("ksh1."))
        val resolveCallsAfterLookup = resolveCalls

        val referencesRaw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "symbol/references",
                    params = JsonObject(
                        mapOf(
                            "workspaceRoot" to JsonPrimitive(tempDir.toString()),
                            "selectorHandle" to JsonPrimitive(selectorHandle),
                            "maxResults" to JsonPrimitive(1),
                        ),
                    ),
                ),
            )
        }
        val referencesResponse = json.decodeFromString(JsonRpcSuccessResponse.serializer(), referencesRaw)
        val references = json.decodeFromJsonElement(
            KastReferencesResponse.serializer(),
            referencesResponse.result,
        )

        assertInstanceOf(KastReferencesAvailableResponse::class.java, references)
        assertEquals(resolveCallsAfterLookup, resolveCalls)
        assertEquals(1, referenceCalls)
    }

    @Test
    fun `selector handle rejections remain distinct and actionable`() {
        val expectedRecoveries = mapOf(
            SelectorHandleAuthority.Resolution.RejectionReason.TAMPERED to "RESOLVE_AGAIN",
            SelectorHandleAuthority.Resolution.RejectionReason.WRONG_WORKSPACE to "RESOLVE_IN_CURRENT_WORKSPACE",
            SelectorHandleAuthority.Resolution.RejectionReason.WRONG_BACKEND to "RESOLVE_WITH_ACTIVE_BACKEND",
            SelectorHandleAuthority.Resolution.RejectionReason.STALE to "RESOLVE_AGAIN",
            SelectorHandleAuthority.Resolution.RejectionReason.FAMILY_NOT_ALLOWED to "CHOOSE_COMPATIBLE_OPERATION",
            SelectorHandleAuthority.Resolution.RejectionReason.UNAVAILABLE to "USE_EXPLICIT_SELECTOR",
        )
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val backend = object : AnalysisBackend by delegate {
            override val selectorHandles: SelectorHandleAuthority = object : SelectorHandleAuthority {
                override fun issue(
                    selector: KastExactSymbolSelector,
                    allowedFamilies: Set<SelectorOperationFamily>,
                ): SelectorHandleAuthority.IssueResult = SelectorHandleAuthority.IssueResult.Unavailable

                override fun resolve(
                    handle: String,
                    workspaceRoot: String,
                    family: SelectorOperationFamily,
                ): SelectorHandleAuthority.Resolution = SelectorHandleAuthority.Resolution.Rejected(
                    SelectorHandleAuthority.Resolution.RejectionReason.valueOf(handle.removePrefix("ksh1.")),
                )
            }
        }
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend,
            config = AnalysisServerConfig(),
        )

        expectedRecoveries.forEach { (reason, expectedRecovery) ->
            val raw = runBlocking {
                dispatcher.dispatch(
                    JsonRpcRequest(
                        id = JsonPrimitive(reason.ordinal + 1),
                        method = "symbol/references",
                        params = JsonObject(
                            mapOf(
                                "workspaceRoot" to JsonPrimitive(tempDir.toString()),
                                "selectorHandle" to JsonPrimitive("ksh1.${reason.name}"),
                            ),
                        ),
                    ),
                )
            }
            val rpc = json.parseToJsonElement(raw).jsonObject
            val result = assertInstanceOf(JsonObject::class.java, rpc["result"])

            assertEquals("SELECTOR_HANDLE_REJECTED", (result["type"] as JsonPrimitive).content)
            assertEquals(reason.name, (result["reason"] as JsonPrimitive).content)
            assertEquals(expectedRecovery, (result["recovery"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `selector handles reuse functions and types across relationship families`() {
        val function = lookupSymbol("sample.Service.run", SymbolKind.FUNCTION, "Service.kt")
        val type = lookupSymbol("sample.Service", SymbolKind.CLASS, "Service.kt")
        val relationships = RecordingPagedRelationshipsBackend(
            ExactLookupBackend(
                delegate = FakeAnalysisBackend.sample(tempDir),
                symbols = listOf(function, type),
            ),
        )
        var resolveCalls = 0
        val backend = object : AnalysisBackend by relationships {
            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolveCalls += 1
                return relationships.resolveSymbol(query)
            }
        }
        val functionHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = function.exactSelector(),
                allowedFamilies = setOf(
                    SelectorOperationFamily.CALLERS,
                    SelectorOperationFamily.CALLEES,
                ),
            ),
        ).handle.value
        val typeHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = type.exactSelector(),
                allowedFamilies = setOf(
                    SelectorOperationFamily.IMPLEMENTATIONS,
                    SelectorOperationFamily.HIERARCHY,
                ),
            ),
        ).handle.value
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend,
            config = AnalysisServerConfig(),
        )

        fun dispatchRelationship(
            method: String,
            selectorHandle: String,
            extraParams: Map<String, JsonElement> = emptyMap(),
        ) {
            val params = mapOf(
                "workspaceRoot" to JsonPrimitive(tempDir.toString()),
                "selectorHandle" to JsonPrimitive(selectorHandle),
            ) + extraParams
            val raw = runBlocking {
                dispatcher.dispatch(
                    JsonRpcRequest(
                        id = JsonPrimitive(method),
                        method = method,
                        params = JsonObject(params),
                    ),
                )
            }
            val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
            assertEquals("AVAILABLE", (success.result.jsonObject["type"] as JsonPrimitive).content)
        }

        dispatchRelationship(
            method = "symbol/callers",
            selectorHandle = functionHandle,
            extraParams = mapOf("direction" to JsonPrimitive("incoming")),
        )
        dispatchRelationship(
            method = "symbol/callers",
            selectorHandle = functionHandle,
            extraParams = mapOf("direction" to JsonPrimitive("outgoing")),
        )
        dispatchRelationship(
            method = "symbol/implementations",
            selectorHandle = typeHandle,
        )
        dispatchRelationship(
            method = "symbol/hierarchy",
            selectorHandle = typeHandle,
            extraParams = mapOf("direction" to JsonPrimitive("BOTH")),
        )

        assertEquals(0, resolveCalls)
        assertEquals(2, relationships.callRelationCalls)
        assertEquals(1, relationships.implementationRelationCalls)
        assertEquals(1, relationships.hierarchyRelationCalls)
    }

    @Test
    fun `selector identity authenticates impact handles without provider resolution`() {
        val symbol = lookupSymbol("sample.Service.run", SymbolKind.FUNCTION, "Service.kt")
        val delegate = FakeAnalysisBackend.sample(tempDir)
        var resolveCalls = 0
        val backend = object : AnalysisBackend by delegate {
            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolveCalls += 1
                return delegate.resolveSymbol(query)
            }
        }
        val selectorHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = symbol.exactSelector(),
                allowedFamilies = setOf(SelectorOperationFamily.IMPACT),
            ),
        ).handle.value
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend,
            config = AnalysisServerConfig(),
        )

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "selector/identity",
                    params = JsonObject(
                        mapOf(
                            "workspaceRoot" to JsonPrimitive(tempDir.toString()),
                            "selectorHandle" to JsonPrimitive(selectorHandle),
                            "family" to JsonPrimitive("IMPACT"),
                        ),
                    ),
                ),
            )
        }
        val rpc = json.parseToJsonElement(raw).jsonObject
        val result = assertInstanceOf(JsonObject::class.java, rpc["result"])
        val identity = assertInstanceOf(JsonObject::class.java, result["identity"])

        assertEquals("AVAILABLE", (result["type"] as JsonPrimitive).content)
        assertEquals(symbol.fqName, (identity["fqName"] as JsonPrimitive).content)
        assertEquals(symbol.location.filePath, (identity["declarationFile"] as JsonPrimitive).content)
        assertEquals(
            symbol.location.startOffset,
            (identity["declarationStartOffset"] as JsonPrimitive).content.toInt(),
        )
        assertEquals(symbol.kind.name, (identity["kind"] as JsonPrimitive).content)
        assertEquals(0, resolveCalls)

        val rejectedRaw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "selector/identity",
                    params = JsonObject(
                        mapOf(
                            "workspaceRoot" to JsonPrimitive(tempDir.toString()),
                            "selectorHandle" to JsonPrimitive(selectorHandle),
                            "family" to JsonPrimitive("RENAME"),
                        ),
                    ),
                ),
            )
        }
        val rejected = assertInstanceOf(
            JsonObject::class.java,
            json.parseToJsonElement(rejectedRaw).jsonObject["result"],
        )

        assertEquals("SELECTOR_HANDLE_REJECTED", (rejected["type"] as JsonPrimitive).content)
        assertEquals("FAMILY_NOT_ALLOWED", (rejected["reason"] as JsonPrimitive).content)
        assertEquals("CHOOSE_COMPATIBLE_OPERATION", (rejected["recovery"] as JsonPrimitive).content)
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `call relationship missing capability degrades without entering traversal`() {
        val symbol = lookupSymbol("sample.Service.run", SymbolKind.FUNCTION, "Service.kt")
        val backend = RecordingPagedRelationshipsBackend(
            delegate = ExactLookupBackend(
                delegate = FakeAnalysisBackend.sample(tempDir),
                symbols = listOf(symbol),
            ),
            missingCapability = ReadCapability.CALL_HIERARCHY,
        )

        val result = dispatchSuccessWithBackend<KastCallersResponse>(
            backend = backend,
            method = "symbol/callers",
            params = json.encodeToJsonElement(
                KastCallersRequest.serializer(),
                KastCallersRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = symbol.exactSelector(),
                ),
            ),
        )

        val degraded = assertInstanceOf(KastCallersDegradedResponse::class.java, result)
        assertEquals(KastCallDegradedReason.CALL_HIERARCHY_UNAVAILABLE, degraded.reason)
        assertEquals(ResultCardinality.KnownMinimum(0), degraded.evidence.cardinality)
        assertEquals(
            listOf(RelationshipSearchLimitation.BACKEND_UNAVAILABLE),
            degraded.evidence.coverage.limitations,
        )
        assertEquals(0, backend.callRelationCalls)
    }

    @Test
    fun `implementation relationship unsupported kind returns without entering traversal`() {
        val symbol = lookupSymbol("sample.Service.run", SymbolKind.FUNCTION, "Service.kt")
        val backend = RecordingPagedRelationshipsBackend(
            ExactLookupBackend(
                delegate = FakeAnalysisBackend.sample(tempDir),
                symbols = listOf(symbol),
            ),
        )

        val result = dispatchSuccessWithBackend<KastImplementationsResponse>(
            backend = backend,
            method = "symbol/implementations",
            params = json.encodeToJsonElement(
                KastImplementationsRequest.serializer(),
                KastImplementationsRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = symbol.exactSelector(),
                ),
            ),
        )

        assertInstanceOf(KastImplementationsUnsupportedSubjectKindResponse::class.java, result)
        assertEquals(0, backend.implementationRelationCalls)
    }

    @Test
    fun `hierarchy relationship budget conflict is a typed degraded zero work outcome`() {
        val symbol = lookupSymbol("sample.Service", SymbolKind.CLASS, "Service.kt")
        val backend = RecordingPagedRelationshipsBackend(
            delegate = ExactLookupBackend(
                delegate = FakeAnalysisBackend.sample(tempDir),
                symbols = listOf(symbol),
            ),
            hierarchyFailure = ConflictException(
                message = "candidate budget reached",
                details = mapOf("continuationFailure" to "candidateBudgetReached"),
            ),
        )

        val result = dispatchSuccessWithBackend<KastHierarchyResponse>(
            backend = backend,
            method = "symbol/hierarchy",
            params = json.encodeToJsonElement(
                KastHierarchyRequest.serializer(),
                KastHierarchyRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = symbol.exactSelector(),
                    direction = TypeHierarchyDirection.BOTH,
                ),
            ),
        )

        val degraded = assertInstanceOf(KastHierarchyDegradedResponse::class.java, result)
        assertEquals(KastHierarchyDegradedReason.CANDIDATE_BUDGET_REACHED, degraded.reason)
        assertEquals(ResultCardinality.KnownMinimum(0), degraded.evidence.cardinality)
        assertEquals(
            listOf(RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED),
            degraded.evidence.coverage.limitations,
        )
        assertEquals(1, backend.hierarchyRelationCalls)
    }

    @Test
    fun `relationship provider timeouts return typed zero-work evidence`() {
        assertRelationshipInterruption(
            mode = RelationshipInterruptionMode.TIMEOUT,
            expectedReason = "TIMEOUT",
            expectedLimitation = RelationshipSearchLimitation.TIMED_OUT,
        )
    }

    @Test
    fun `relationship provider cancellation returns typed zero-work evidence`() {
        assertRelationshipInterruption(
            mode = RelationshipInterruptionMode.CANCELLED,
            expectedReason = "CANCELLED",
            expectedLimitation = RelationshipSearchLimitation.CANCELLED,
        )
    }

    private fun assertRelationshipInterruption(
        mode: RelationshipInterruptionMode,
        expectedReason: String,
        expectedLimitation: RelationshipSearchLimitation,
    ) {
        val function = lookupSymbol("sample.Service.run", SymbolKind.FUNCTION, "Service.kt")
        val type = lookupSymbol("sample.Service", SymbolKind.CLASS, "Service.kt", startOffset = 40)
        val backend = InterruptingRelationshipsBackend(
            delegate = ExactLookupBackend(
                delegate = FakeAnalysisBackend.sample(tempDir),
                symbols = listOf(function, type),
            ),
            mode = mode,
        )
        val requests = listOf(
            "symbol/references" to json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(tempDir.toString(), selector = function.exactSelector()),
            ),
            "symbol/callers" to json.encodeToJsonElement(
                KastCallersRequest.serializer(),
                KastCallersRequest(tempDir.toString(), selector = function.exactSelector()),
            ),
            "symbol/implementations" to json.encodeToJsonElement(
                KastImplementationsRequest.serializer(),
                KastImplementationsRequest(tempDir.toString(), selector = type.exactSelector()),
            ),
            "symbol/hierarchy" to json.encodeToJsonElement(
                KastHierarchyRequest.serializer(),
                KastHierarchyRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = type.exactSelector(),
                    direction = TypeHierarchyDirection.BOTH,
                ),
            ),
        )
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig())

        requests.forEachIndexed { index, (method, params) ->
            val raw = runBlocking {
                dispatcher.dispatch(
                    JsonRpcRequest(
                        method = method,
                        params = params,
                        id = JsonPrimitive(index + 1),
                    ),
                )
            }
            assertTrue("result" in json.parseToJsonElement(raw).jsonObject, raw)
            val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
            val result = success.result.jsonObject
            val evidence = checkNotNull(result["evidence"]).jsonObject
            val cardinality = checkNotNull(evidence["cardinality"]).jsonObject
            val coverage = checkNotNull(evidence["coverage"]).jsonObject
            val limitations = checkNotNull(coverage["limitations"]).jsonArray
                .map { limitation -> (limitation as JsonPrimitive).content }

            assertEquals("DEGRADED", (result["type"] as JsonPrimitive).content, method)
            assertEquals(expectedReason, (result["reason"] as JsonPrimitive).content, method)
            assertEquals("KNOWN_MINIMUM", (cardinality["type"] as JsonPrimitive).content, method)
            assertEquals("0", (cardinality["knownMinimumCount"] as JsonPrimitive).content, method)
            assertTrue(expectedLimitation.name in limitations, "$method: $limitations")
        }
    }

    @Test
    fun `symbol resolve returns not found instead of a fuzzy candidate`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(lookupSymbol("sample.LegacyOrderService", SymbolKind.CLASS, "LegacyOrderService.kt")),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "MissingOrderService"),
            ),
        )

        assertInstanceOf(KastResolveNotFoundResponse::class.java, result)
    }

    @Test
    fun `symbol resolve returns ambiguous for overloaded exact members`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 10),
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 40),
            ),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "parse"),
            ),
        )

        val ambiguous = assertInstanceOf(KastResolveAmbiguousResponse::class.java, result)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `symbol resolve cardinality is independent of server presentation limit`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 10),
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 40),
            ),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            config = AnalysisServerConfig(maxResults = 1),
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "parse"),
            ),
        )

        val ambiguous = assertInstanceOf(KastResolveAmbiguousResponse::class.java, result)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `symbol resolve matches backticked simple and qualified names exactly`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(lookupSymbol("sample.when", SymbolKind.FUNCTION, "Keywords.kt")),
        )

        val simple = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "`when`"),
            ),
        )
        val qualified = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "sample.`when`"),
            ),
        )

        assertEquals("sample.when", assertInstanceOf(KastResolveSuccessResponse::class.java, simple).symbol.fqName)
        assertEquals("sample.when", assertInstanceOf(KastResolveSuccessResponse::class.java, qualified).symbol.fqName)
    }

    @Test
    fun `symbol resolve applies kind file and containing type as hard constraints`() {
        val fileName = "Parser.kt"
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(
                lookupSymbol(
                    fqName = "sample.Parser.parse",
                    kind = SymbolKind.FUNCTION,
                    fileName = fileName,
                    containingDeclaration = "sample.Parser",
                ),
            ),
        )
        val mismatches = listOf(
            KastResolveRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "parse",
                kind = WrapperNamedSymbolKind.CLASS,
            ),
            KastResolveRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "parse",
                fileHint = tempDir.resolve("Other.kt").toString(),
            ),
            KastResolveRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "parse",
                containingType = "sample.OtherParser",
            ),
        )

        mismatches.forEach { request ->
            val result = dispatchSuccessWithBackend<KastResolveResponse>(
                backend = backend,
                method = "symbol/resolve",
                params = json.encodeToJsonElement(KastResolveRequest.serializer(), request),
            )
            assertInstanceOf(KastResolveNotFoundResponse::class.java, result)
        }
    }

    @Test
    fun `symbol resolve applies containing type using resolved compiler identity`() {
        val workspaceSymbol = lookupSymbol(
            fqName = "sample.Parser.parse",
            kind = SymbolKind.FUNCTION,
            fileName = "Parser.kt",
            containingDeclaration = null,
        )
        val resolvedSymbol = workspaceSymbol.copy(containingDeclaration = "sample.Parser")
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(workspaceSymbol),
            resolvedSymbols = listOf(resolvedSymbol),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "parse",
                    containingType = "sample.Parser",
                ),
            ),
        )

        val success = assertInstanceOf(KastResolveSuccessResponse::class.java, result)
        assertEquals("sample.Parser", success.symbol.containingDeclaration)
    }

    @Test
    fun `symbol discover ranks contextual candidates and returns resolve requests`() {
        val file = sampleTypeFile()

        val result = dispatchSuccess<KastDiscoverResponse>(
            method = "symbol/discover",
            params = json.encodeToJsonElement(
                KastDiscoverRequest.serializer(),
                KastDiscoverRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "Greeter",
                    fileHint = file.toString(),
                    line = 4,
                    codeSnippet = "open class FriendlyGreeter",
                    kind = WrapperNamedSymbolKind.CLASS,
                    maxResults = 2,
                ),
            ),
        )

        val success = result as KastDiscoverSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(2, success.candidates.size)
        assertEquals("sample.FriendlyGreeter", success.candidates.first().symbol.fqName)
        assertEquals(1, success.candidates.first().rank)
        assertEquals("symbol/resolve", success.candidates.first().nextRequest.method)
        assertEquals(WrapperNamedSymbolKind.CLASS, success.candidates.first().resolveParams.kind)
        assertEquals(file.toString(), success.candidates.first().resolveParams.fileHint)
        assertEquals(true, success.page?.truncated)
    }

    @Test
    fun `symbol discover rejects non positive max results`() {
        val response = dispatchRaw(
            method = "symbol/discover",
            params = json.encodeToJsonElement(
                KastDiscoverRequest.serializer(),
                KastDiscoverRequest(symbol = "greet", maxResults = 0),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `symbol references sends typed bounds to backend and continuation has no duplicates`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val delegate = FakeAnalysisBackend.contractFixture(fixture)
        val observedQueries = mutableListOf<ParsedReferencesQuery>()
        val backend = object : AnalysisBackend by delegate {
            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult {
                observedQueries += query
                return delegate.findReferences(query)
            }
        }
        val selector = KastExactSymbolSelector(
            fqName = fixture.symbolFqName,
            declarationFile = fixture.declarationLocation.filePath,
            declarationStartOffset = fixture.declarationLocation.startOffset,
            kind = SymbolKind.FUNCTION,
        )

        val firstResult = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                ),
            ),
        )

        val firstPage = assertInstanceOf(KastReferencesAvailableResponse::class.java, firstResult)
        assertEquals(ResultCardinality.KnownMinimum(2), firstPage.cardinality)
        assertEquals(1, firstPage.references.size)
        assertTrue(checkNotNull(firstPage.page).truncated)
        assertTrue(firstPage.page?.nextPageToken != null)

        val secondResult = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                    pageToken = checkNotNull(firstPage.page?.nextPageToken),
                ),
            ),
        )

        val secondPage = assertInstanceOf(KastReferencesAvailableResponse::class.java, secondResult)
        assertEquals(ResultCardinality.Exact(2), secondPage.cardinality)
        assertEquals(1, secondPage.references.size)
        assertEquals(null, secondPage.page)
        assertTrue(firstPage.references.single() !in secondPage.references)
        assertEquals(2, observedQueries.size)
        assertEquals(1, observedQueries[0].maxResults.value)
        assertEquals(null, observedQueries[0].pageToken)
        assertEquals(1, observedQueries[1].maxResults.value)
        assertEquals(firstPage.page?.nextPageToken, observedQueries[1].pageToken?.value)
    }

    @Test
    fun `symbol references preserves an honest paginated result when candidate coverage is complete`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val delegate = FakeAnalysisBackend.contractFixture(fixture)
        val backend = object : AnalysisBackend by delegate {
            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult =
                delegate.findReferences(query).copy(
                    searchScope = SearchScope(
                        visibility = SymbolVisibility.PUBLIC,
                        scope = SearchScopeKind.DEPENDENT_MODULES,
                        exhaustive = false,
                        candidateCoverage = SearchScope.CandidateCoverage.COMPLETE,
                        candidateFileCount = 2,
                        searchedFileCount = 1,
                    ),
                )
        }
        val selector = KastExactSymbolSelector(
            fqName = fixture.symbolFqName,
            declarationFile = fixture.declarationLocation.filePath,
            declarationStartOffset = fixture.declarationLocation.startOffset,
            kind = SymbolKind.FUNCTION,
        )

        val result = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                ),
            ),
        )

        val available = assertInstanceOf(KastReferencesAvailableResponse::class.java, result)
        assertFalse(checkNotNull(available.searchScope).exhaustive)
        assertEquals(SearchScope.CandidateCoverage.COMPLETE, available.searchScope?.candidateCoverage)
        assertTrue(checkNotNull(available.page).truncated)
    }

    @Test
    fun `symbol references degrades when the underlying candidate search is partial`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val delegate = FakeAnalysisBackend.contractFixture(fixture)
        val backend = object : AnalysisBackend by delegate {
            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult =
                delegate.findReferences(query).copy(
                    searchScope = SearchScope(
                        visibility = SymbolVisibility.PUBLIC,
                        scope = SearchScopeKind.DEPENDENT_MODULES,
                        exhaustive = false,
                        candidateCoverage = SearchScope.CandidateCoverage.PARTIAL,
                        candidateFileCount = 2,
                        searchedFileCount = 1,
                    ),
                )
        }
        val selector = KastExactSymbolSelector(
            fqName = fixture.symbolFqName,
            declarationFile = fixture.declarationLocation.filePath,
            declarationStartOffset = fixture.declarationLocation.startOffset,
            kind = SymbolKind.FUNCTION,
        )

        val result = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                ),
            ),
        )

        assertInstanceOf(KastReferencesDegradedResponse::class.java, result)
    }

    @Test
    fun `symbol references rejects a selector that does not match the anchored declaration`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val result = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = FakeAnalysisBackend.contractFixture(fixture),
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = KastExactSymbolSelector(
                        fqName = "sample.notGreet",
                        declarationFile = fixture.declarationLocation.filePath,
                        declarationStartOffset = fixture.declarationLocation.startOffset,
                        kind = SymbolKind.FUNCTION,
                    ),
                ),
            ),
        )

        val mismatch = assertInstanceOf(KastReferencesSubjectIdentityMismatchResponse::class.java, result)
        assertEquals(fixture.symbolFqName, mismatch.actual.fqName)
    }

    @Test
    fun `symbol references rejects non positive max results`() {
        val response = dispatchRaw(
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    selector = KastExactSymbolSelector(
                        fqName = "sample.greet",
                        declarationFile = tempDir.resolve("Greeter.kt").toString(),
                        declarationStartOffset = 0,
                        kind = SymbolKind.FUNCTION,
                    ),
                    maxResults = 0,
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `symbol resolve returns requested declaration documentation and surrounding text`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                    includeDeclarationScope = true,
                    includeDocumentation = true,
                    surroundingLines = 2,
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        assertEquals("sample.greet", success.symbol.fqName)
        assertTrue(checkNotNull(success.symbol.declarationScope).sourceText!!.contains("fun greet"))
        assertTrue(checkNotNull(success.symbol.documentation).contains("Greets"))
        val context = checkNotNull(success.context)
        assertTrue(checkNotNull(context.surroundingText).text.contains("fun use() = greet()"))
    }

    @Test
    fun `symbol resolve returns lightweight surrounding members`() {
        val file = sampleTypeFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "FriendlyGreeter",
                    fileHint = file.toString(),
                    includeSurroundingMembers = true,
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        val memberNames = checkNotNull(success.context?.surroundingMembers).map { it.fqName }
        assertEquals(listOf("sample.Greeter", "sample.LoudGreeter"), memberNames)
        assertTrue(success.context!!.surroundingMembers!!.all { it.declarationScope == null })
    }

    @Test
    fun `symbol rename dispatches rename apply and diagnostics`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastRenameResponse>(
            method = "symbol/rename",
            params = json.encodeToJsonElement(
                KastRenameRequest.serializer(),
                KastRenameBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                    newName = "hello",
                ),
            ),
        )

        val success = result as KastRenameSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(1, success.affectedFiles.size)
        assertTrue(file.readText().contains("fun hello()"))
    }

    @Test
    fun `selector handle renames exact subject without name resolution`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val file = sampleFile()
        val selector = KastExactSymbolSelector(
            fqName = "sample.greet",
            declarationFile = file.toString(),
            declarationStartOffset = file.readText().indexOf("greet"),
            kind = SymbolKind.FUNCTION,
            containingType = "sample",
        )
        var resolveCalls = 0
        var renameCalls = 0
        val backend = object : AnalysisBackend by delegate {
            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolveCalls += 1
                return delegate.resolveSymbol(query)
            }

            override suspend fun rename(query: ParsedRenameQuery): RenameResult {
                renameCalls += 1
                return delegate.rename(query)
            }
        }
        val selectorHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = selector,
                allowedFamilies = setOf(SelectorOperationFamily.RENAME),
            ),
        ).handle.value
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/rename",
                    params = json.encodeToJsonElement(
                        KastRenameRequest.serializer(),
                        KastRenameBySelectorHandleRequest(
                            workspaceRoot = tempDir.toString(),
                            selectorHandle = selectorHandle,
                            newName = "hello",
                        ),
                    ),
                ),
            )
        }
        val rpc = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastRenameResponse.serializer(), rpc.result)

        assertInstanceOf(KastRenameSuccessResponse::class.java, result)
        assertTrue(file.readText().contains("fun hello()"))
        assertEquals(0, resolveCalls)
        assertEquals(1, renameCalls)
    }

    @Test
    fun `rename backend cannot omit affected files to bypass refresh preflight`() {
        val backend = MissingRefreshRenameBackend(FakeAnalysisBackend.sample(tempDir))
        val file = sampleFile()
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/rename",
                    params = json.encodeToJsonElement(
                        KastRenameRequest.serializer(),
                        KastRenameBySymbolRequest(
                            workspaceRoot = tempDir.toString(),
                            symbol = "greet",
                            fileHint = file.toString(),
                            newName = "hello",
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code)
        assertEquals(0, backend.applyCalls)
        assertTrue(file.readText().contains("fun greet()"))
    }

    @Test
    fun `symbol write and validate insert computes file hashes before apply`() {
        val backend = CapturingApplyEditsBackend(FakeAnalysisBackend.sample(tempDir))
        val file = sampleFile()
        val originalContent = file.readText()
        val content = "\nfun added() = Unit\n"
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/write-and-validate",
                    params = json.encodeToJsonElement(
                        KastWriteAndValidateRequest.serializer(),
                        KastWriteAndValidateInsertAtOffsetRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = file.toString(),
                            offset = originalContent.length,
                            content = content,
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(
            KastWriteAndValidateResponse.serializer(),
            response.result,
        )

        val success = result as KastWriteAndValidateSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(1, success.appliedEdits)
        assertEquals(
            listOf(file.toString() to FileHashing.sha256(originalContent)),
            backend.appliedFileHashes,
        )
        assertTrue(file.readText().endsWith(content))
    }

    @Test
    fun `raw diagnostics preserves incomplete semantic evidence`() {
        val file = sampleFile()
        val backend = IncompleteDiagnosticsBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "raw/diagnostics",
                    params = json.encodeToJsonElement(
                        DiagnosticsQuery.serializer(),
                        DiagnosticsQuery(filePaths = listOf(file.toString())),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(DiagnosticsResult.serializer(), response.result)

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.BACKEND_FAILURE, result.fileStatuses.single().state)
        assertEquals(1, result.requestedFileCount)
        assertEquals(0, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
    }

    @Test
    fun `mutation summary fails closed when post edit analysis is incomplete`() {
        val file = sampleFile()
        val backend = IncompleteDiagnosticsBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/write-and-validate",
                    params = json.encodeToJsonElement(
                        KastWriteAndValidateRequest.serializer(),
                        KastWriteAndValidateInsertAtOffsetRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = file.toString(),
                            offset = file.readText().length,
                            content = "\nfun added() = Unit\n",
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastWriteAndValidateResponse.serializer(), response.result)

        val success = result as KastWriteAndValidateSuccessResponse
        assertFalse(success.ok)
        assertFalse(success.diagnostics.clean)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, success.diagnostics.semanticOutcome)
        assertEquals(1, success.diagnostics.requestedFileCount)
        assertEquals(0, success.diagnostics.analyzedFileCount)
        assertEquals(1, success.diagnostics.skippedFileCount)
    }

    @Test
    fun `mutation summary remains dirty when an error is beyond the returned diagnostic limit`() {
        val file = sampleFile()
        val backend = CompilerDiagnosticsBeyondLimitBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend,
            config = AnalysisServerConfig(maxResults = 1),
        )
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/write-and-validate",
                    params = json.encodeToJsonElement(
                        KastWriteAndValidateRequest.serializer(),
                        KastWriteAndValidateInsertAtOffsetRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = file.toString(),
                            offset = file.readText().length,
                            content = "\nfun added() = Unit\n",
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastWriteAndValidateResponse.serializer(), response.result)

        val success = result as KastWriteAndValidateSuccessResponse
        assertFalse(success.ok)
        assertFalse(success.diagnostics.clean)
        assertEquals(1, success.diagnostics.errorCount)
        assertEquals(1, success.diagnostics.warningCount)
        assertEquals(listOf("LATE_COMPILER_ERROR"), success.diagnostics.errors.map(Diagnostic::code))
    }

    @Test
    fun `symbol add file dispatches file creation and diagnostics`() {
        FakeAnalysisBackend.sample(tempDir)
        val targetFile = tempDir.resolve("src").resolve("Added.kt")
        val contentFile = tempDir.resolve("added-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Added\n")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/add-file",
            params = json.encodeToJsonElement(
                KastAddFileRequest.serializer(),
                KastAddFileRequest(
                    workspaceRoot = tempDir.toString(),
                    filePath = targetFile.toString(),
                    contentFile = contentFile.toString(),
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.ADD_FILE, success.operation)
        assertEquals(true, success.applied)
        assertEquals(1, success.editCount)
        assertEquals(listOf(targetFile.toString()), success.createdFiles)
        assertEquals("package sample\n\nclass Added\n", targetFile.readText())
    }

    @Test
    fun `symbol add file refreshes semantic admission before optimization and diagnostics`() {
        val backend = RecordingMutationBackend(FakeAnalysisBackend.sample(tempDir))
        val targetFile = tempDir.resolve("src").resolve("Added.kt")
        val contentFile = tempDir.resolve("added-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Added\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }

        assertEquals(
            listOf("apply", "refresh", "optimize", "diagnostics"),
            backend.operations,
        )
    }

    @Test
    fun `symbol add file fails closed when semantic admission remains incomplete`() {
        val backend = RecordingMutationBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            incompleteRefresh = true,
        )
        val targetFile = tempDir.resolve("src").resolve("Pending.kt")
        val contentFile = tempDir.resolve("pending-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Pending\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastScopeMutationResponse.serializer(), response.result)

        val success = result as KastScopeMutationSuccessResponse
        assertFalse(success.ok)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, success.diagnostics.semanticOutcome)
        assertEquals(1, success.diagnostics.requestedFileCount)
        assertEquals(0, success.diagnostics.analyzedFileCount)
        assertEquals(1, success.diagnostics.skippedFileCount)
        assertEquals(listOf("apply", "refresh"), backend.operations)
    }

    @Test
    fun `symbol add file preflights refresh capability before creating the file`() {
        val backend = MissingRefreshCapabilityBackend(FakeAnalysisBackend.sample(tempDir))
        val targetFile = tempDir.resolve("src").resolve("NoRefresh.kt")
        val contentFile = tempDir.resolve("no-refresh-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass NoRefresh\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code)
        assertEquals(0, backend.applyCalls)
        assertFalse(Files.exists(targetFile))
    }

    @Test
    fun `symbol add declaration dispatches file scope insertion`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("declaration-content.kt")
        Files.writeString(contentFile, "\nfun added() = Unit\n")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/add-declaration",
            params = json.encodeToJsonElement(
                KastAddDeclarationRequest.serializer(),
                KastAddDeclarationRequest(
                    workspaceRoot = tempDir.toString(),
                    placement = KastPlacementSelector(
                        scope = KastFilePlacementScope(targetFile.toString()),
                        anchor = KastAtPlacementAnchor(KastPlacementAnchor.FILE_BOTTOM),
                    ),
                    contentFile = contentFile.toString(),
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.ADD_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertEquals(targetFile.toString(), success.placement?.filePath)
        assertTrue(targetFile.readText().endsWith("\nfun added() = Unit\n"))
    }

    @Test
    fun `symbol add declaration after symbol uses declaration scope end`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("after-declaration-content.kt")
        Files.writeString(contentFile, "\nfun added() = Unit\n")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/add-declaration",
            params = json.encodeToJsonElement(
                KastAddDeclarationRequest.serializer(),
                KastAddDeclarationRequest(
                    workspaceRoot = tempDir.toString(),
                    placement = KastPlacementSelector(
                        scope = KastFilePlacementScope(targetFile.toString()),
                        anchor = KastAfterSymbolPlacementAnchor(
                            symbol = "greet",
                            fileHint = targetFile.toString(),
                            kind = WrapperNamedSymbolKind.FUNCTION,
                        ),
                    ),
                    contentFile = contentFile.toString(),
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.ADD_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertTrue(targetFile.readText().contains("fun greet() = \"hi\"\nfun added() = Unit\n"))
        assertFalse(targetFile.readText().contains("fun greet\nfun added()"))
    }

    @Test
    fun `scope mutation request interfaces do not change wire payloads`() {
        val request = KastAddStatementRequest(
            workspaceRoot = tempDir.toString(),
            insideScope = "sample.greet",
            anchor = KastStatementPlacementAnchor.BODY_END,
            contentFile = tempDir.resolve("statement.kt").toString(),
        )

        val payload = json.encodeToJsonElement(KastAddStatementRequest.serializer(), request).jsonObject

        assertEquals(KastScopeMutationOperation.ADD_STATEMENT, request.operation)
        assertEquals(NormalizedPath.ofAbsolute(tempDir), request.requestedWorkspaceRoot)
        assertEquals(NonBlankString("sample.greet"), request.requestedInsideScope)
        assertEquals(NormalizedPath.ofAbsolute(tempDir.resolve("statement.kt")), request.contentFilePath)
        assertFalse(payload.containsKey("operation"))
        assertFalse(payload.containsKey("requestedWorkspaceRoot"))
        assertFalse(payload.containsKey("requestedInsideScope"))
        assertFalse(payload.containsKey("contentFilePath"))
        assertEquals(JsonPrimitive("body-end"), payload["anchor"])
    }

    @Test
    fun `symbol replace declaration dispatches declaration scope edit`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("replacement-content.kt")
        Files.writeString(contentFile, "fun greet() = \"bye\"")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/replace-declaration",
            params = json.encodeToJsonElement(
                KastReplaceDeclarationRequest.serializer(),
                KastReplaceDeclarationBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    contentFile = contentFile.toString(),
                    fileHint = targetFile.toString(),
                    kind = WrapperNamedSymbolKind.FUNCTION,
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.REPLACE_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertEquals(1, success.editCount)
        assertTrue(targetFile.readText().contains("fun greet() = \"bye\""))
        assertFalse(targetFile.readText().contains("fun greet() = \"hi\""))
    }

    @Test
    fun `selector handle replaces exact declaration without named discovery`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val targetFile = sampleFile()
        val declarationOffset = targetFile.readText().indexOf("greet")
        val replacementFile = tempDir.resolve("handle-replacement-content.kt")
        Files.writeString(replacementFile, "fun greet() = \"handle\"")
        var workspaceSymbolCalls = 0
        val resolvedPositions = mutableListOf<Pair<String, Int>>()
        val backend = object : AnalysisBackend by delegate {
            override suspend fun workspaceSymbolSearch(
                query: ParsedWorkspaceSymbolQuery,
            ): WorkspaceSymbolResult {
                workspaceSymbolCalls += 1
                return delegate.workspaceSymbolSearch(query)
            }

            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolvedPositions += query.position.filePath.value to query.position.offset.value
                return delegate.resolveSymbol(query)
            }
        }
        val selectorHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = KastExactSymbolSelector(
                    fqName = "sample.greet",
                    declarationFile = targetFile.toString(),
                    declarationStartOffset = declarationOffset,
                    kind = SymbolKind.FUNCTION,
                    containingType = "sample",
                ),
                allowedFamilies = setOf(SelectorOperationFamily.REPLACE_DECLARATION),
            ),
        ).handle.value
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/replace-declaration",
                    params = json.encodeToJsonElement(
                        KastReplaceDeclarationRequest.serializer(),
                        KastReplaceDeclarationBySelectorHandleRequest(
                            workspaceRoot = tempDir.toString(),
                            selectorHandle = selectorHandle,
                            contentFile = replacementFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val rpc = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastScopeMutationResponse.serializer(), rpc.result)

        assertInstanceOf(KastScopeMutationSuccessResponse::class.java, result)
        assertTrue(targetFile.readText().contains("fun greet() = \"handle\""))
        assertEquals(0, workspaceSymbolCalls)
        assertEquals(listOf(targetFile.toString() to declarationOffset), resolvedPositions)
    }

    @Test
    fun `symbol replace declaration resolves fully qualified names through simple-name search`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("fq-replacement-content.kt")
        Files.writeString(contentFile, "fun greet() = \"fq\"")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/replace-declaration",
            params = json.encodeToJsonElement(
                KastReplaceDeclarationRequest.serializer(),
                KastReplaceDeclarationBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "sample.greet",
                    contentFile = contentFile.toString(),
                    kind = WrapperNamedSymbolKind.FUNCTION,
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.REPLACE_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertEquals(1, success.editCount)
        assertTrue(targetFile.readText().contains("fun greet() = \"fq\""))
        assertFalse(targetFile.readText().contains("fun greet() = \"hi\""))
    }

    @Test
    fun `references dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ReferencesResult>(
            method = "raw/references",
            params = json.encodeToJsonElement(
                ReferencesQuery.serializer(),
                ReferencesQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    includeDeclaration = true,
                ),
            ),
        )

        assertEquals("sample.greet", result.declaration?.fqName)
        assertEquals(1, result.references.size)
    }

    @Test
    fun `dispatcher maps request timeout to timeout api error`() {
        val dispatcher = RpcAnalysisDispatcher(
            backend = DispatcherTimeoutHealthBackend(FakeAnalysisBackend.sample(tempDir), delayMillis = 100),
            config = AnalysisServerConfig(requestTimeoutMillis = 1),
        )
        val raw = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "health"))
        }

        val response = json.parseToJsonElement(raw).jsonObject
        val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

        assertEquals("TIMEOUT", error.error.data?.code)
        assertEquals(true, error.error.data?.retryable)
        assertEquals("health", error.error.data?.details?.get("method"))
        assertEquals("1", error.error.data?.details?.get("timeoutMillis"))
    }

    @Test
    fun `dispatcher maps backend cancellation to timeout api error`() {
        val dispatcher = RpcAnalysisDispatcher(
            backend = DispatcherCancellationHealthBackend(FakeAnalysisBackend.sample(tempDir)),
            config = AnalysisServerConfig(requestTimeoutMillis = 1),
        )
        val raw = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "health"))
        }

        val response = json.parseToJsonElement(raw).jsonObject
        val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

        assertEquals("TIMEOUT", error.error.data?.code)
        assertEquals(true, error.error.data?.retryable)
        assertEquals("health", error.error.data?.details?.get("method"))
    }

    @Test
    fun `call hierarchy dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<CallHierarchyResult>(
            method = "raw/call-hierarchy",
            params = json.encodeToJsonElement(
                CallHierarchyQuery.serializer(),
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    direction = CallDirection.INCOMING,
                    depth = 1,
                ),
            ),
        )

        assertEquals("sample.greet", result.root.symbol.fqName)
        assertEquals(2, result.stats.totalNodes)
    }

    @Test
    fun `type hierarchy dispatches without HTTP`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")

        val result = dispatchSuccess<TypeHierarchyResult>(
            method = "raw/type-hierarchy",
            params = json.encodeToJsonElement(
                TypeHierarchyQuery.serializer(),
                TypeHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                    direction = TypeHierarchyDirection.BOTH,
                    depth = 1,
                ),
            ),
        )

        assertEquals("sample.FriendlyGreeter", result.root.symbol.fqName)
        assertEquals(listOf("sample.Greeter", "sample.LoudGreeter"), result.root.children.map { child -> child.symbol.fqName })
    }

    @Test
    fun `semantic insertion point dispatches without HTTP`() {
        dispatcher()
        val file = sampleFile()
        val content = file.readText()

        val result = dispatchSuccess<SemanticInsertionResult>(
            method = "raw/semantic-insertion-point",
            params = json.encodeToJsonElement(
                SemanticInsertionQuery.serializer(),
                SemanticInsertionQuery(
                    position = FilePosition(filePath = file.toString(), offset = 0),
                    target = SemanticInsertionTarget.FILE_BOTTOM,
                ),
            ),
        )

        assertEquals(content.length, result.insertionOffset)
        assertEquals(file.toString(), result.filePath)
    }

    @Test
    fun `rename dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RenameResult>(
            method = "raw/rename",
            params = json.encodeToJsonElement(
                RenameQuery.serializer(),
                RenameQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    newName = "welcome",
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(result.edits.all { edit -> edit.newText == "welcome" })
    }

    @Test
    fun `imports optimize dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ImportOptimizeResult>(
            method = "raw/optimize-imports",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(
                    filePaths = listOf(file.toString()),
                ),
            ),
        )

        assertTrue(result.edits.isEmpty())
        assertTrue(result.affectedFiles.isEmpty())
    }

    @Test
    fun `apply edits dispatches without HTTP`() {
        dispatcher()
        val file = sampleFile()
        val originalContent = file.readText()
        val result = dispatchSuccess<ApplyEditsResult>(
            method = "raw/apply-edits",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = file.toString(),
                            startOffset = 20,
                            endOffset = 25,
                            newText = "hello",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            filePath = file.toString(),
                            hash = FileHashing.sha256(originalContent),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(file.readText().contains("hello"))
    }

    @Test
    fun `apply edits validates absolute file operation paths`() {
        val response = dispatchRaw(
            method = "raw/apply-edits",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = "relative/New.kt",
                            content = "class New",
                        ),
                    ),
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `imports optimize validates absolute file paths`() {
        val response = dispatchRaw(
            method = "raw/optimize-imports",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(filePaths = listOf("relative/File.kt")),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace refresh dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RefreshResult>(
            method = "raw/workspace-refresh",
            params = json.encodeToJsonElement(
                RefreshQuery.serializer(),
                RefreshQuery(filePaths = listOf(file.toString())),
            ),
        )

        assertEquals(listOf(file.toString()), result.refreshedFiles)
        assertTrue(result.removedFiles.isEmpty())
        assertEquals(false, result.fullRefresh)
    }

    @Test
    fun `file outline dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<FileOutlineResult>(
            method = "raw/file-outline",
            params = json.encodeToJsonElement(
                FileOutlineQuery.serializer(),
                FileOutlineQuery(filePath = file.toString()),
            ),
        )

        assertTrue(result.symbols.isNotEmpty())
        assertEquals("sample.greet", result.symbols.first().symbol.fqName)
    }

    @Test
    fun `file outline validates absolute file path`() {
        val response = dispatchRaw(
            method = "raw/file-outline",
            params = json.encodeToJsonElement(
                FileOutlineQuery.serializer(),
                FileOutlineQuery(filePath = "relative/File.kt"),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace files dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceFilesResult>(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(),
            ),
        )

        assertTrue(result.modules.isNotEmpty())
        assertEquals("fake-module", result.modules.first().name)
    }

    @Test
    fun `workspace files filters by module name`() {
        val result = dispatchSuccess<WorkspaceFilesResult>(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(moduleName = "nonexistent"),
            ),
        )

        assertTrue(result.modules.isEmpty())
    }

    @Test
    fun `workspace files rejects blank module name`() {
        val response = dispatchRaw(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(moduleName = "  "),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace files rejects non positive file cap`() {
        val response = dispatchRaw(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(includeFiles = true, maxFilesPerModule = 0),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace files rejects file cap above server max results`() {
        val response = dispatchRaw(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(includeFiles = true, maxFilesPerModule = 501),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace symbol dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceSymbolResult>(
            method = "raw/workspace-symbol",
            params = json.encodeToJsonElement(
                WorkspaceSymbolQuery.serializer(),
                WorkspaceSymbolQuery(pattern = "greet"),
            ),
        )

        assertTrue(result.symbols.isNotEmpty())
        assertEquals("sample.greet", result.symbols.first().fqName)
    }

    @Test
    fun `workspace symbol rejects blank pattern`() {
        val response = dispatchRaw(
            method = "raw/workspace-symbol",
            params = json.encodeToJsonElement(
                WorkspaceSymbolQuery.serializer(),
                WorkspaceSymbolQuery(pattern = "  "),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace symbol rejects zero max results`() {
        val response = dispatchRaw(
            method = "raw/workspace-symbol",
            params = json.encodeToJsonElement(
                WorkspaceSymbolQuery.serializer(),
                WorkspaceSymbolQuery(pattern = "greet", maxResults = 0),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace search dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceSearchResult>(
            method = "raw/workspace-search",
            params = json.encodeToJsonElement(
                WorkspaceSearchQuery.serializer(),
                WorkspaceSearchQuery(pattern = "greet"),
            ),
        )

        assertTrue(result.matches.isNotEmpty())
        assertTrue(result.matches.first().preview.contains("greet"))
    }

    @Test
    fun `workspace search rejects blank pattern`() {
        val response = dispatchRaw(
            method = "raw/workspace-search",
            params = json.encodeToJsonElement(
                WorkspaceSearchQuery.serializer(),
                WorkspaceSearchQuery(pattern = "  "),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `implementations dispatches without HTTP`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")
        val result = dispatchSuccess<ImplementationsResult>(
            method = "raw/implementations",
            params = json.encodeToJsonElement(
                ImplementationsQuery.serializer(),
                ImplementationsQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                ),
            ),
        )
        assertEquals("sample.Greeter", result.declaration.fqName)
        assertTrue(result.implementations.isNotEmpty())
    }

    @Test
    fun `code actions dispatches without HTTP`() {
        val file = sampleFile()
        val result = dispatchSuccess<CodeActionsResult>(
            method = "raw/code-actions",
            params = json.encodeToJsonElement(
                CodeActionsQuery.serializer(),
                CodeActionsQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                ),
            ),
        )
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `completions dispatches without HTTP`() {
        val file = sampleFile()
        val result = dispatchSuccess<CompletionsResult>(
            method = "raw/completions",
            params = json.encodeToJsonElement(
                CompletionsQuery.serializer(),
                CompletionsQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                ),
            ),
        )
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `invalid diagnostics params return rpc error payload`() {
        val response = dispatchRaw(
            method = "raw/diagnostics",
            params = json.encodeToJsonElement(
                DiagnosticsQuery.serializer(),
                DiagnosticsQuery(filePaths = listOf("relative/File.kt")),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
        assertTrue(checkNotNull(error.error.data?.details?.get("filePath")).contains("relative/File.kt"))
    }

    @Test
    fun `invalid call hierarchy max total calls returns rpc error payload`() {
        val file = sampleFile()
        val response = dispatchRaw(
            method = "raw/call-hierarchy",
            params = json.encodeToJsonElement(
                CallHierarchyQuery.serializer(),
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    direction = CallDirection.OUTGOING,
                    depth = 0,
                    maxTotalCalls = 0,
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `invalid type hierarchy max results returns rpc error payload`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")
        val response = dispatchRaw(
            method = "raw/type-hierarchy",
            params = json.encodeToJsonElement(
                TypeHierarchyQuery.serializer(),
                TypeHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                    direction = TypeHierarchyDirection.SUBTYPES,
                    depth = 1,
                    maxResults = 0,
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    private fun sampleFile(): Path = tempDir.resolve("src").resolve("Sample.kt")

    private fun sampleTypeFile(): Path = tempDir.resolve("src").resolve("Types.kt")

    private fun dispatcher(): RpcAnalysisDispatcher = RpcAnalysisDispatcher(
        backend = FakeAnalysisBackend.sample(tempDir),
        config = AnalysisServerConfig(),
    )

    private inline fun <reified T> dispatchSuccess(
        method: String,
        params: JsonElement? = null,
    ): T {
        val response = dispatchRaw(method, params)
        val success = json.decodeFromJsonElement(
            JsonRpcSuccessResponse.serializer(),
            response,
        )
        return json.decodeFromJsonElement(
            serializer<T>(),
            success.result,
        )
    }

    private inline fun <reified T> dispatchSuccessWithBackend(
        backend: AnalysisBackend,
        config: AnalysisServerConfig = AnalysisServerConfig(),
        method: String,
        params: JsonElement? = null,
    ): T {
        val raw = runBlocking {
            RpcAnalysisDispatcher(backend = backend, config = config).dispatch(
                JsonRpcRequest(id = JsonPrimitive(1), method = method, params = params),
            )
        }
        val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        return json.decodeFromJsonElement(serializer<T>(), success.result)
    }

    private fun lookupSymbol(
        fqName: String,
        kind: SymbolKind,
        fileName: String,
        startOffset: Int = 10,
        containingDeclaration: String? = null,
    ): Symbol = Symbol(
        fqName = fqName,
        kind = kind,
        location = Location(
            filePath = tempDir.resolve(fileName).toString(),
            startOffset = startOffset,
            endOffset = startOffset + fqName.substringAfterLast('.').length,
            startLine = startOffset,
            startColumn = 1,
            preview = fqName,
        ),
        containingDeclaration = containingDeclaration,
    )

    private fun Symbol.exactSelector(): KastExactSymbolSelector = KastExactSymbolSelector(
        fqName = fqName,
        declarationFile = location.filePath,
        declarationStartOffset = location.startOffset,
        kind = kind,
        containingType = containingDeclaration,
    )

    private fun dispatchRaw(
        method: String,
        params: JsonElement? = null,
    ): JsonObject {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = method,
            params = params,
        )
        val raw = runBlocking {
            dispatcher().dispatch(request)
        }
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun classFileText(clazz: Class<*>): String =
        checkNotNull(clazz.getResourceAsStream("${clazz.simpleName}.class")) {
            "Missing class file resource for ${clazz.name}"
        }.use { input ->
            String(input.readBytes(), StandardCharsets.ISO_8859_1)
        }

}

private class DispatcherTimeoutHealthBackend(
    private val delegate: AnalysisBackend,
    private val delayMillis: Long,
) : AnalysisBackend by delegate {
    override suspend fun health() = run {
        delay(delayMillis)
        delegate.health()
    }
}

private class DispatcherCancellationHealthBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun health() = throw CancellationException("backend cancelled")
}

private enum class RelationshipInterruptionMode {
    TIMEOUT,
    CANCELLED,
}

private class InterruptingRelationshipsBackend(
    private val delegate: AnalysisBackend,
    private val mode: RelationshipInterruptionMode,
) : AnalysisBackend by delegate {
    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult = interrupt()

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult = interrupt()

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult = interrupt()

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult = interrupt()

    private suspend fun interrupt(): Nothing = when (mode) {
        RelationshipInterruptionMode.TIMEOUT -> withTimeout(1) {
            delay(100)
            error("Relationship provider timeout was not enforced")
        }
        RelationshipInterruptionMode.CANCELLED -> throw CancellationException("Relationship provider cancelled")
    }
}

private class CapturingApplyEditsBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    var appliedFileHashes: List<Pair<String, String>> = emptyList()
        private set

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        appliedFileHashes = query.fileHashes.map { fileHash ->
            fileHash.filePath.value to fileHash.hash
        }
        return delegate.applyEdits(query)
    }
}

private class RecordingMutationBackend(
    private val delegate: AnalysisBackend,
    private val incompleteRefresh: Boolean = false,
) : AnalysisBackend by delegate {
    val operations = mutableListOf<String>()

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        operations += "apply"
        return delegate.applyEdits(query)
    }

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        operations += "refresh"
        if (!incompleteRefresh) return delegate.refresh(query)
        return RefreshResult.focused(
            fileStatuses = query.filePaths.map { filePath ->
                SemanticAdmissionStatus.incomplete(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    analysisAvailability = AnalysisAvailabilityState.PENDING,
                    analysisStatus = FileAnalysisStatus.skipped(
                        filePath,
                        FileAnalysisState.PENDING_INDEX,
                        "IDEA is indexing",
                    ),
                )
            },
            attemptCount = 3,
            elapsedMillis = 50,
        )
    }

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult {
        operations += "optimize"
        return delegate.optimizeImports(query)
    }

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        operations += "diagnostics"
        return delegate.diagnostics(query)
    }
}

private class MissingRefreshCapabilityBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    var applyCalls: Int = 0
        private set

    override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
        mutationCapabilities = delegate.capabilities().mutationCapabilities - MutationCapability.REFRESH_WORKSPACE,
    )

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyCalls += 1
        return delegate.applyEdits(query)
    }
}

private class MissingRefreshRenameBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    var applyCalls: Int = 0
        private set

    override suspend fun capabilities(): BackendCapabilities {
        val capabilities = delegate.capabilities()
        return capabilities.copy(
            mutationCapabilities = capabilities.mutationCapabilities - MutationCapability.REFRESH_WORKSPACE,
        )
    }

    override suspend fun rename(query: ParsedRenameQuery): RenameResult {
        val result = delegate.rename(query)
        return RenameResult.of(
            edits = result.edits,
            fileHashes = result.fileHashes,
            searchScope = result.searchScope,
        )
    }

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyCalls += 1
        return delegate.applyEdits(query)
    }
}

private class IncompleteDiagnosticsBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val fileStatuses = query.filePaths.value.map { filePath ->
            FileAnalysisStatus.skipped(
                filePath = filePath,
                state = FileAnalysisState.BACKEND_FAILURE,
                message = "Semantic analysis was unavailable after the operation",
            )
        }
        val diagnostics = query.filePaths.value.map { filePath ->
            Diagnostic(
                location = Location(
                    filePath = filePath.value,
                    startOffset = 0,
                    endOffset = 0,
                    startLine = 0,
                    startColumn = 0,
                    preview = "",
                ),
                severity = DiagnosticSeverity.ERROR,
                message = "Semantic analysis was unavailable after the operation",
                code = "ANALYSIS_FAILURE",
            )
        }
        return DiagnosticsResult.of(
            diagnostics = diagnostics,
            fileStatuses = fileStatuses,
            fileHashes = emptyList(),
        )
    }
}

private class CompilerDiagnosticsBeyondLimitBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val filePath = query.filePaths.value.single()
        fun diagnostic(
            severity: DiagnosticSeverity,
            offset: Int,
            code: String,
        ): Diagnostic = Diagnostic(
            location = Location(
                filePath = filePath.value,
                startOffset = offset,
                endOffset = offset,
                startLine = 0,
                startColumn = 0,
                preview = "",
            ),
            severity = severity,
            message = code,
            code = code,
        )
        return DiagnosticsResult.of(
            diagnostics = listOf(
                diagnostic(DiagnosticSeverity.WARNING, 0, "EARLY_WARNING"),
                diagnostic(DiagnosticSeverity.ERROR, 1, "LATE_COMPILER_ERROR"),
            ),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(filePath)),
            fileHashes = listOf(
                FileHash(filePath.value, FileHashing.sha256(Files.readString(Path.of(filePath.value)))),
            ),
        )
    }
}

private class ExactLookupBackend(
    private val delegate: AnalysisBackend,
    private val symbols: List<Symbol>,
    private val resolvedSymbols: List<Symbol> = symbols,
) : AnalysisBackend by delegate {
    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult =
        WorkspaceSymbolResult(symbols = symbols)

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult = SymbolResult(
        symbol = resolvedSymbols.single { symbol ->
            symbol.location.filePath == query.position.filePath.value &&
                symbol.location.startOffset == query.position.offset.value
        },
    )
}

private class RecordingPagedRelationshipsBackend(
    private val delegate: AnalysisBackend,
    private val missingCapability: ReadCapability? = null,
    private val hierarchyFailure: ConflictException? = null,
) : AnalysisBackend by delegate {
    var callRelationCalls: Int = 0
        private set
    var implementationRelationCalls: Int = 0
        private set
    var hierarchyRelationCalls: Int = 0
        private set

    override suspend fun capabilities(): BackendCapabilities {
        val capabilities = delegate.capabilities()
        return if (missingCapability == null) {
            capabilities
        } else {
            capabilities.copy(readCapabilities = capabilities.readCapabilities - missingCapability)
        }
    }

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult {
        callRelationCalls += 1
        return CallRelationsResult.Available(emptyList(), emptyRelationPage())
    }

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult {
        implementationRelationCalls += 1
        return ImplementationRelationsResult.Available(emptyList(), emptyRelationPage())
    }

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult {
        hierarchyRelationCalls += 1
        hierarchyFailure?.let { throw it }
        return HierarchyRelationsResult.Available(emptyList(), emptyRelationPage())
    }

    private fun emptyRelationPage(): RelationTraversalPageInfo = RelationTraversalPageInfo.create(
        evidence = RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(0),
            coverage = RelationshipSearchCoverage.complete(),
        ),
        returnedCount = 0,
        returnedBefore = 0,
        visitedCandidateCount = 0,
        candidateVisitLimit = 16_384,
        nextHandle = null,
    )
}
