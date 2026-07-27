package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherSelectorTest : AnalysisDispatcherTestSupport() {
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
}
