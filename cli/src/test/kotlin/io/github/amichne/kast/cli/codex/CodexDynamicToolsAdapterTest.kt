package io.github.amichne.kast.cli.codex

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolInspectTarget
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexDynamicToolsAdapterTest {
    @Test
    fun `malformed arguments and unknown identities fail before Kast execution`() {
        val kast = RecordingKastReads()
        val adapter = CodexDynamicToolsAdapter(kast)

        assertEquals(
            CodexDynamicToolCallResult.Rejected(CodexDynamicToolFailure.INVALID_ARGUMENTS),
            adapter.call("kast", "symbol_inspect", json("{}")),
        )
        assertEquals(
            CodexDynamicToolCallResult.Rejected(CodexDynamicToolFailure.INVALID_ARGUMENTS),
            adapter.call(
                "kast",
                "relation_read",
                json("""{"exactSelector":"exact:v1:opaque","relation":"unknown"}"""),
            ),
        )
        assertEquals(
            CodexDynamicToolCallResult.Rejected(CodexDynamicToolFailure.UNKNOWN_TOOL),
            adapter.call("other", "symbol_inspect", json("""{"query":"Target"}""")),
        )

        assertEquals(emptyList<String>(), kast.calls)
        assertEquals(3, adapter.metrics().malformedInvocations)
    }

    @Test
    fun `canonical selector passes unchanged from inspection into relation request`() {
        val selector = text("exact:v1:opaque-selector")
        val symbol = symbol(selector)
        val kast = RecordingKastReads(selector, symbol)
        val adapter = CodexDynamicToolsAdapter(kast)

        assertEquals(
            CodexDynamicToolCallResult.Succeeded(DESCRIBE_JSON),
            adapter.call(
                "kast",
                "symbol_inspect",
                json("""{"query":"CanonicalSymbolDiscoverHandler"}"""),
            ),
        )
        assertEquals(
            CodexDynamicToolCallResult.Succeeded(RELATION_JSON),
            adapter.call(
                "kast",
                "relation_read",
                json(
                    """{"exactSelector":"${selector.value}","relation":"callers"}""",
                ),
            ),
        )

        assertEquals(listOf("discover", "inspect", "relation"), kast.calls)
        assertEquals(selector, kast.relationRequest?.exactSelector)
        assertEquals(RelationKindDocument.CALLERS, kast.relationRequest?.relation)
        assertEquals(
            SymbolDiscoverTargetDocument.Name(
                text("CanonicalSymbolDiscoverHandler"),
                io.github.amichne.kast.protocol.contract.SymbolNameKindDocument.SYMBOL,
                SymbolDiscoveryMatchDocument.EXACT_NAME,
            ),
            kast.discoveryRequest?.target,
        )
        val metrics = adapter.metrics()
        assertEquals(2, metrics.dynamicToolCalls)
        assertEquals(0, metrics.malformedInvocations)
        assertEquals(0, metrics.correctiveInvocations)
        assertTrue(metrics.selectorRoundTripUnchanged)
    }

    @Test
    fun `reconstructed selector text is rejected before relation execution`() {
        val selector = text("exact:v1:opaque-selector")
        val kast = RecordingKastReads(selector, symbol(selector))
        val adapter = CodexDynamicToolsAdapter(kast)

        adapter.call(
            "kast",
            "symbol_inspect",
            json("""{"query":"CanonicalSymbolDiscoverHandler"}"""),
        )
        val result = adapter.call(
            "kast",
            "relation_read",
            json("""{"exactSelector":"CanonicalSymbolDiscoverHandler","relation":"callers"}"""),
        )

        assertEquals(
            CodexDynamicToolCallResult.Rejected(CodexDynamicToolFailure.SELECTOR_NOT_REUSED),
            result,
        )
        assertFalse("relation" in kast.calls)
        assertFalse(adapter.metrics().selectorRoundTripUnchanged)
    }

    @Test
    fun `one repeated relation call is counted as one corrective invocation`() {
        val selector = text("exact:v1:opaque-selector")
        val adapter = CodexDynamicToolsAdapter(RecordingKastReads(selector, symbol(selector)))

        adapter.call(
            "kast",
            "symbol_inspect",
            json("""{"query":"CanonicalSymbolDiscoverHandler"}"""),
        )
        repeat(2) {
            adapter.call(
                "kast",
                "relation_read",
                json("""{"exactSelector":"${selector.value}","relation":"callers"}"""),
            )
        }

        assertEquals(1, adapter.metrics().correctiveInvocations)
    }

    @Test
    fun `rejected selector does not erase the produced selector proof`() {
        val selector = text("exact:v1:opaque-selector")
        val kast = RecordingKastReads(selector, symbol(selector))
        val adapter = CodexDynamicToolsAdapter(kast)
        adapter.call(
            "kast",
            "symbol_inspect",
            json("""{"query":"CanonicalSymbolDiscoverHandler"}"""),
        )

        val rejected = adapter.call(
            "kast",
            "relation_read",
            json("""{"exactSelector":"exact:v1:changed","relation":"callers"}"""),
        )
        val retried = adapter.call(
            "kast",
            "relation_read",
            json("""{"exactSelector":"${selector.value}","relation":"callers"}"""),
        )

        assertEquals(
            CodexDynamicToolCallResult.Rejected(CodexDynamicToolFailure.SELECTOR_NOT_REUSED),
            rejected,
        )
        assertEquals(CodexDynamicToolCallResult.Succeeded(RELATION_JSON), retried)
        assertEquals(0, adapter.metrics().correctiveInvocations)
        assertTrue(adapter.metrics().selectorRoundTripUnchanged)
        assertEquals(1, kast.calls.count { it == "relation" })
    }

    @Test
    fun `qualified relation evidence is rejected without erasing selector proof`() {
        val selector = text("exact:v1:opaque-selector")
        val kast = RecordingKastReads(selector, symbol(selector), qualifiedRelation = true)
        val adapter = CodexDynamicToolsAdapter(kast)
        adapter.call(
            "kast",
            "symbol_inspect",
            json("""{"query":"CanonicalSymbolDiscoverHandler"}"""),
        )

        val result = adapter.call(
            "kast",
            "relation_read",
            json("""{"exactSelector":"${selector.value}","relation":"callers"}"""),
        )

        assertEquals(
            CodexDynamicToolCallResult.Rejected(CodexDynamicToolFailure.KAST_OPERATION_REJECTED),
            result,
        )
        assertEquals(listOf("PROVIDER_INCOMPLETE"), adapter.metrics().relationQualificationNames)
        assertFalse(adapter.metrics().selectorRoundTripUnchanged)
    }

    @Test
    fun `Kast CLI execution detection accepts shell quoting without matching source paths`() {
        assertTrue("/bin/zsh -lc 'kast symbol discover --help'".invokesKastCli())
        assertTrue("kast relation read --selector opaque".invokesKastCli())
        assertFalse("sed -n '1p' cli/codex/kast/source.kt".invokesKastCli())
    }

    private class RecordingKastReads(
        private val exactSelector: ProtocolText = text("exact:v1:unused"),
        private val exactSymbol: SymbolDocument = symbol(exactSelector),
        private val qualifiedRelation: Boolean = false,
    ) : CanonicalKastReadOperations {
        val calls = mutableListOf<String>()
        var discoveryRequest: SymbolDiscoverRequest? = null
        var relationRequest: RelationReadRequest? = null

        override fun discover(request: SymbolDiscoverRequest): CanonicalKastReadAttempt<
            SymbolDiscoverResult,
            SymbolDiscoverQualification,
            SymbolDiscoverRejection,
            > {
            calls += "discover"
            discoveryRequest = request
            val candidate: SymbolDiscoveryDocument = SymbolDiscoveryDocument.Declaration(
                candidateSelector = text("candidate:v1:opaque"),
                kind = SymbolDiscoveryKindDocument.SYMBOL,
                name = text("CanonicalSymbolDiscoverHandler"),
                file = text("runtime/composition/CanonicalSymbolHandlers.kt"),
                offset = offset(0),
            )
            return read(
                CanonicalOperation.SYMBOL_DISCOVER,
                SymbolDiscoverResult(BoundedProtocolList.create(listOf(candidate)).refined()),
                "discover-json",
            )
        }

        override fun inspect(request: SymbolInspectRequest): CanonicalKastReadAttempt<
            SymbolInspectResult,
            SymbolInspectQualification,
            SymbolInspectRejection,
            > {
            calls += "inspect"
            assertEquals(
                text("candidate:v1:opaque"),
                (request.target as SymbolInspectTarget.Candidate).selector,
            )
            return read(
                CanonicalOperation.SYMBOL_INSPECT,
                SymbolInspectResult(exactSymbol),
                DESCRIBE_JSON,
            )
        }

        override fun relation(request: RelationReadRequest): CanonicalKastReadAttempt<
            RelationReadResult,
            RelationReadQualification,
            RelationReadRejection,
            > {
            calls += "relation"
            relationRequest = request
            if (qualifiedRelation) {
                val result = RelationReadResult(
                    BoundedProtocolList.create(emptyList<RelationFactDocument>()).refined(),
                )
                return CanonicalKastReadAttempt.Read(
                    CanonicalKastRead(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(CanonicalOperation.RELATION_READ.id, generation(), result),
                            RelationReadQualification.resumable(
                                RelationKnownMinimumDocument.parse(0).refined(),
                                listOf(RelationLimitationDocument.PROVIDER_INCOMPLETE),
                                relationContinuation("dynamic-tool"),
                            ).refined(),
                        ),
                        RELATION_JSON,
                    ),
                )
            }
            return read(
                CanonicalOperation.RELATION_READ,
                RelationReadResult(
                    BoundedProtocolList.create(listOf(relation(exactSymbol))).refined(),
                ),
                RELATION_JSON,
            )
        }

        private fun <
            Result : io.github.amichne.kast.protocol.contract.OperationResult,
            Qualification : io.github.amichne.kast.protocol.contract.OperationQualification,
            Rejection : io.github.amichne.kast.protocol.contract.OperationRejection,
            > read(
            operation: CanonicalOperation,
            result: Result,
            document: String,
        ): CanonicalKastReadAttempt<Result, Qualification, Rejection> =
            CanonicalKastReadAttempt.Read(
                CanonicalKastRead(
                    OperationOutcome.Complete(
                        EvidenceEnvelope(operation.id, generation(), result),
                    ),
                    document,
                ),
            )
    }

    companion object {
        private const val DESCRIBE_JSON = "describe-json"
        private const val RELATION_JSON = "relation-json"

        private val strictJson = Json { ignoreUnknownKeys = false }

        private fun json(raw: String) = strictJson.parseToJsonElement(raw)

        private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

        private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

        private fun generation(): EvidenceGeneration = EvidenceGeneration.parse(1).refined()

        private fun symbol(selector: ProtocolText): SymbolDocument {
            val qualifiedIdentity = text(
                "io.github.amichne.kast.runtime.composition.protocol." +
                    "CanonicalSymbolDiscoverHandler",
            )
            val signature = CompilerSignatureDocument.ClassLike(qualifiedIdentity)
            val compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
            return SymbolDocument.create(
                selector = selector,
                kind = SymbolKindDocument.CLASSLIKE,
                name = text("CanonicalSymbolDiscoverHandler"),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(qualifiedIdentity),
                file = text("runtime/composition/CanonicalSymbolHandlers.kt"),
                range = SourceRangeDocument.create(offset(0), offset(10)).refined(),
                compilerEvidence = compilerEvidence,
            ).refined()
        }

        private fun relation(symbol: SymbolDocument): RelationFactDocument = RelationFactDocument(
            meaning = RelationKindDocument.REFERENCES,
            source = symbol,
            target = symbol,
            occurrence = RelationOccurrenceDocument(
                text("candidate:occurrence"),
                symbol.file,
                symbol.range,
            ),
            provenance = RelationProvenanceDocument.K2_AUTHORED_SOURCE,
            coverage = RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
        )

        private fun relationContinuation(payloadText: String): RelationContinuationDocument {
            val payload = payloadText.toByteArray()
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            return RelationContinuationDocument.parse(
                "relation-continuation:v1:$encoded:$digest",
            ).refined()
        }

        private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("unexpected fixture rejection: $failure")
        }
    }
}
