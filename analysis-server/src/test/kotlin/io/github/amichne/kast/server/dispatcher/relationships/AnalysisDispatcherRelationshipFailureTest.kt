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

class AnalysisDispatcherRelationshipFailureTest : AnalysisDispatcherTestSupport() {
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
}
