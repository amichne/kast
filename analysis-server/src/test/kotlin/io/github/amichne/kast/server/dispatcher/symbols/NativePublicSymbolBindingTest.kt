package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
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

class NativePublicSymbolBindingTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `native public symbol binding bypasses aggregate search and resolve authority`() {
        val symbol = lookupSymbol("sample.NativeGreeter", SymbolKind.CLASS, "NativeGreeter.kt")
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val nativeSelectorHandles = DigestSelectorHandleAuthority(
            workspaceRoot = tempDir.toAbsolutePath().normalize().toString(),
            backendName = "native-intellij",
            backendVersion = "test",
            backendInstanceId = "native-public-symbol-binding-test",
            semanticGeneration = { 19L },
        )
        val aggregateBackend = object : AnalysisBackend by delegate {
            override suspend fun workspaceSymbolSearch(
                query: ParsedWorkspaceSymbolQuery,
            ): WorkspaceSymbolResult = error("aggregate workspace symbol search was invoked")

            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult =
                error("aggregate symbol resolution was invoked")

            override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult =
                error("aggregate file outline was invoked")

            override suspend fun runtimeStatus(): RuntimeStatusResponse =
                error("aggregate runtime status was invoked")
        }
        val evidence = KastReadEvidence.NativeIntellij(
            generation = 19L,
            completeness = KastNativeReadCompleteness.EXACT,
            qualifications = emptySet(),
            stages = KastNativeReadStages(
                KastNativeReadStage.entries.associateWith {
                    if (it == KastNativeReadStage.IPC) {
                        KastReadStageObservation.OutsideResponseBoundary
                    } else {
                        KastReadStageObservation.Measured(1L)
                    }
                },
            ),
            work = KastNativeReadWork(
                vfsRefreshCount = 0L,
                gradleImportCount = 0L,
                graphBuildCount = 0L,
                sqliteWriteCount = 0L,
                readActionCount = 1L,
            ),
            projectionBytes = 128L,
        )
        val observedMatches = mutableListOf<PublicSymbolReadMatch>()
        val binding = PublicSymbolReadBinding.Native(
            workspaceRoot = NormalizedPath.ofAbsolute(tempDir),
            selectorHandles = nativeSelectorHandles,
            reader = { query ->
                observedMatches += query.match
                val issued = nativeSelectorHandles.issue(
                    symbol.toExactSelector(),
                    symbol.kind.selectorOperationFamilies(),
                ) as SelectorHandleAuthority.IssueResult.Issued
                NativePublicSymbolReadResult.Completed(
                    definitions = listOf(
                        NativePublicSymbolReadResult.Definition(symbol, issued.handle),
                    ),
                    evidence = evidence,
                )
            },
        )
        val dispatcher = RpcAnalysisDispatcher(
            backend = aggregateBackend,
            config = AnalysisServerConfig(),
            publicSymbolReads = binding,
        )

        val discoveryResponse = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/discover",
                    params = json.encodeToJsonElement(
                        KastDiscoverRequest.serializer(),
                        KastDiscoverRequest(
                            symbol = "NativeGreeter",
                            maxResults = 1,
                        ),
                    ),
                ),
            )
        }
        val discoveryEnvelope =
            json.decodeFromString(JsonRpcSuccessResponse.serializer(), discoveryResponse)
        val discovery = json.decodeFromJsonElement(
            KastDiscoverResponse.serializer(),
            discoveryEnvelope.result,
        ) as KastDiscoverSuccessResponse
        assertTrue(
            checkNotNull(discovery.candidates.single().resolveParams.fileHint)
                .endsWith("NativeGreeter.kt"),
        )
        val selectorIdentityResponse = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(3),
                    method = "selector/identity",
                    params = json.encodeToJsonElement(
                        KastSelectorIdentityRequest.serializer(),
                        KastSelectorIdentityRequest(
                            selectorHandle = discovery.candidates.single().selectorHandle,
                            family = SelectorOperationFamily.IDENTITY,
                        ),
                    ),
                ),
            )
        }
        val selectorIdentityEnvelope =
            json.decodeFromString(JsonRpcSuccessResponse.serializer(), selectorIdentityResponse)
        val selectorIdentity = json.decodeFromJsonElement(
            KastSelectorIdentityResponse.serializer(),
            selectorIdentityEnvelope.result,
        ) as KastSelectorIdentityAvailableResponse
        assertEquals(symbol.fqName, selectorIdentity.identity.fqName)

        val response = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "symbol/resolve",
                    params = json.encodeToJsonElement(
                        KastResolveRequest.serializer(),
                        KastResolveRequest(
                            symbol = "NativeGreeter",
                        ),
                    ),
                ),
            )
        }
        val envelope = json.decodeFromString(JsonRpcSuccessResponse.serializer(), response)
        val success = json.decodeFromJsonElement(
            KastResolveResponse.serializer(),
            envelope.result,
        ) as KastResolveSuccessResponse

        assertEquals("sample.NativeGreeter", success.symbol.fqName)
        assertEquals(evidence, success.readEvidence)
        assertEquals(
            listOf(PublicSymbolReadMatch.FUZZY, PublicSymbolReadMatch.EXACT_NAME),
            observedMatches,
        )
    }
}
