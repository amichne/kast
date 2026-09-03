package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalReadGeneratedSerializationTest {
    @Test
    fun `generated request document preserves shape and rejects malformed fields`() {
        val codec = CanonicalReadSerializers.relationReadRequest
        val request = RelationReadRequest(
            exactSelector = text("exact:Target"),
            relation = RelationKindDocument.CALLERS,
            limit = count(25),
        )
        val document = json(
            """{"exactSelector":"exact:Target","relation":"callers","limit":25,"position":{"type":"start"}}""",
        )

        assertEquals(WireValueEncoding.Encoded(document), codec.encode(request, WireValueRole.REQUEST))
        assertEquals(WireDecoding.Decoded(request), codec.decode(document, WireValueRole.REQUEST))
        listOf(
            """{"exactSelector":"exact:Target","relation":"callers"}""",
            """{"exactSelector":"exact:Target","relation":"callers","limit":25,"position":{"type":"start"},"extra":true}""",
            """{"exactSelector":"exact:Target","relation":"unknown","limit":25,"position":{"type":"start"}}""",
            """{"exactSelector":"exact:Target","relation":"callers","limit":0,"position":{"type":"start"}}""",
        ).forEach { malformed ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
                codec.decode(json(malformed), WireValueRole.REQUEST),
            )
        }
        val continuation = relationContinuation("resume")
        val resumed = request.copy(position = RelationReadPositionDocument.Resume(continuation))
        val resumedDocument = json(
            """{"exactSelector":"exact:Target","relation":"callers","limit":25,"position":{"type":"resume","continuation":"${continuation.value}"}}""",
        )
        assertEquals(
            WireValueEncoding.Encoded(resumedDocument),
            codec.encode(resumed, WireValueRole.REQUEST),
        )
        assertEquals(
            WireDecoding.Decoded(resumed),
            codec.decode(resumedDocument, WireValueRole.REQUEST),
        )
    }

    @Test
    fun `traversal request round trips closed start and resume positions`() {
        val codec = CanonicalReadSerializers.traversalRunRequest
        val start = TraversalRunRequest(
            text("exact:Target"),
            RelationKindDocument.CALLEES,
            count(3),
            count(25),
        )
        val startDocument = json(
            """{"exactSelector":"exact:Target","relation":"callees","maximumDepth":3,"maximumResults":25,"position":{"type":"start"}}""",
        )
        assertEquals(WireValueEncoding.Encoded(startDocument), codec.encode(start, WireValueRole.REQUEST))
        assertEquals(WireDecoding.Decoded(start), codec.decode(startDocument, WireValueRole.REQUEST))

        val continuation = traversalContinuation("resume")
        val resume = start.copy(
            position = TraversalRunPositionDocument.Resume(continuation),
        )
        val resumeDocument = json(
            """{"exactSelector":"exact:Target","relation":"callees","maximumDepth":3,"maximumResults":25,"position":{"type":"resume","continuation":"${continuation.value}"}}""",
        )
        assertEquals(
            WireValueEncoding.Encoded(resumeDocument),
            codec.encode(resume, WireValueRole.REQUEST),
        )
        assertEquals(WireDecoding.Decoded(resume), codec.decode(resumeDocument, WireValueRole.REQUEST))
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
            codec.decode(
                json(
                    """{"exactSelector":"exact:Target","relation":"callees","maximumDepth":3,"maximumResults":25,"position":{"type":"resume","continuation":"bad"}}""",
                ),
                WireValueRole.REQUEST,
            ),
        )
    }

    @Test
    fun `generated qualification document preserves hyphenated names and closed variants`() {
        val codec = CanonicalReadSerializers.symbolDiscoverQualification
        val qualification = SymbolDiscoverQualification.from(
            setOf(
                SymbolDiscoverLimitation.WORK_LIMIT,
                SymbolDiscoverLimitation.PROVIDER_FAILURE,
            ),
        ).refinedValue()
        val document = json("""{"limitations":["work-limit","provider-failure"]}""")

        assertEquals(
            WireValueEncoding.Encoded(document),
            codec.encode(qualification, WireValueRole.QUALIFICATION),
        )
        assertEquals(
            WireDecoding.Decoded(qualification),
            codec.decode(document, WireValueRole.QUALIFICATION),
        )
        listOf(
            """{}""",
            """{"limitations":[]}""",
            """{"limitations":["unknown"]}""",
            """{"limitations":["work-limit"],"extra":true}""",
        ).forEach { malformed ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION)),
                codec.decode(json(malformed), WireValueRole.QUALIFICATION),
            )
        }
    }

    @Test
    fun `proof carrying qualifications round trip and malformed claims fail closed`() {
        val continuation = relationContinuation("qualification")
        val relation = RelationReadQualification.resumable(
            RelationKnownMinimumDocument.parse(2).refinedValue(),
            listOf(
                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            continuation,
        ).refinedValue()
        assertQualification(
            CanonicalReadSerializers.relationReadQualification,
            relation,
            """{"type":"resumable","knownMinimum":2,"limitations":["result_limit_reached","provider_incomplete"],"continuation":"${continuation.value}"}""",
            listOf(
                """{"type":"resumable","knownMinimum":2,"limitations":["provider_incomplete","result_limit_reached"],"continuation":"${continuation.value}"}""",
                """{"type":"resumable","knownMinimum":2,"limitations":["provider_incomplete"],"continuation":"bad"}""",
            ),
        )
        val terminal = RelationReadQualification.terminalIncomplete(
            RelationKnownMinimumDocument.parse(0).refinedValue(),
            listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
        ).refinedValue()
        assertQualification(
            CanonicalReadSerializers.relationReadQualification,
            terminal,
            """{"type":"terminal_incomplete","knownMinimum":0,"limitations":["unresolved_target"]}""",
            listOf(
                """{"type":"terminal_incomplete","knownMinimum":0,"limitations":[],"continuation":"bad"}""",
            ),
        )

        val traversalContinuation = traversalContinuation("qualification")
        val traversal = TraversalRunQualification.resumable(
            listOf(
                TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
            ),
            listOf(RelationLimitationDocument.PROVIDER_INCOMPLETE),
            traversalContinuation,
        ).refinedValue()
        assertQualification(
            CanonicalReadSerializers.traversalRunQualification,
            traversal,
            """{"type":"resumable","limitations":["record_limit_reached","one_hop_incomplete"],"relationLimitations":["provider_incomplete"],"continuation":"${traversalContinuation.value}"}""",
            listOf(
                """{"type":"resumable","limitations":["one_hop_incomplete"],"relationLimitations":[],"continuation":"${traversalContinuation.value}"}""",
            ),
        )
        val terminalTraversal = TraversalRunQualification.terminalIncomplete(
            listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
            listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
        ).refinedValue()
        assertQualification(
            CanonicalReadSerializers.traversalRunQualification,
            terminalTraversal,
            """{"type":"terminal_incomplete","limitations":["one_hop_incomplete"],"relationLimitations":["unresolved_target"]}""",
            listOf(
                """{"type":"terminal_incomplete","limitations":["one_hop_incomplete"],"relationLimitations":[]}""",
            ),
        )

        val diagnostic = DiagnosticCheckQualification.create(
            DiagnosticKnownCountDocument.parse(3).refinedValue(),
            resultLimitReached = true,
            analyzedFiles = listOf(text("src/A.kt")),
            limitations = listOf(
                DiagnosticLimitationDocument(
                    text("src/B.kt"),
                    DiagnosticLimitationReasonDocument.INDEXING,
                ),
            ),
        ).refinedValue()
        assertQualification(
            CanonicalReadSerializers.diagnosticCheckQualification,
            diagnostic,
            """{"knownDiagnosticCount":3,"resultLimitReached":true,"analyzedFiles":["src/A.kt"],"limitations":[{"file":"src/B.kt","reason":"indexing"}]}""",
            listOf(
                """{"knownDiagnosticCount":0,"resultLimitReached":false,"analyzedFiles":[],"limitations":[]}""",
                """{"knownDiagnosticCount":3,"resultLimitReached":true,"analyzedFiles":["src/A.kt"],"limitations":[{"file":"src/A.kt","reason":"indexing"}]}""",
            ),
        )
    }

    @Test
    fun `generated empty and list documents reject unknown missing and invalid content`() {
        val resultCodec = CanonicalReadSerializers.diagnosticCheckResult
        val result = DiagnosticCheckResult(
            BoundedProtocolList.create(
                listOf(
                    DiagnosticDocument(
                        DiagnosticSeverityDocument.WARNING,
                        text("UNUSED"),
                        text("unused"),
                        DiagnosticLocationDocument(
                            text("candidate:v2:diagnostic"),
                            text("src/A.kt"),
                            DiagnosticRangeDocument.create(offset(7), offset(7)).refinedValue(),
                        ),
                    ),
                ),
            ).refinedValue(),
        )
        val document = json(
            """{"diagnostics":[{"severity":"warning","code":"UNUSED","message":"unused","location":{"candidateSelector":"candidate:v2:diagnostic","file":"src/A.kt","range":{"startInclusive":7,"endExclusive":7}}}]}""",
        )
        assertEquals(WireValueEncoding.Encoded(document), resultCodec.encode(result, WireValueRole.RESULT))
        assertEquals(WireDecoding.Decoded(result), resultCodec.decode(document, WireValueRole.RESULT))
        listOf(
            """{}""",
            """{"diagnostics":[{"severity":"unknown","code":"UNUSED","message":"unused","location":{"file":"src/A.kt","range":{"startInclusive":7,"endExclusive":7}}}]}""",
            """{"diagnostics":[],"extra":true}""",
        ).forEach { malformed ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                resultCodec.decode(json(malformed), WireValueRole.RESULT),
            )
        }
    }

    private fun json(document: String): JsonElement = wireJson.parseToJsonElement(document)

    private fun <Qualification> assertQualification(
        codec: WireValueCodec<Qualification>,
        qualification: Qualification,
        encoded: String,
        malformed: List<String>,
    ) {
        val document = json(encoded)
        assertEquals(
            WireValueEncoding.Encoded(document),
            codec.encode(qualification, WireValueRole.QUALIFICATION),
        )
        assertEquals(
            WireDecoding.Decoded(qualification),
            codec.decode(document, WireValueRole.QUALIFICATION),
        )
        malformed.forEach { raw ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION)),
                codec.decode(json(raw), WireValueRole.QUALIFICATION),
            )
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refinedValue()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refinedValue()

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
        ).refinedValue()
    }

    private fun traversalContinuation(payloadText: String): TraversalContinuationDocument {
        val payload = payloadText.toByteArray()
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return TraversalContinuationDocument.parse(
            "traversal-continuation:v1:$encoded:$digest",
        ).refinedValue()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
