package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolPublicSurfaceWireTest {
    @Test
    fun `all discovery target variants round trip through one canonical operation`() {
        val targets = listOf(
            SymbolDiscoverTargetDocument.Name(
                text("Controller"),
                SymbolNameKindDocument.SYMBOL,
                SymbolDiscoveryMatchDocument.EXACT_NAME,
            ),
            SymbolDiscoverTargetDocument.Location(text("src/Controller.kt"), offset(27)),
            SymbolDiscoverTargetDocument.Structure(text("src/Controller.kt")),
            SymbolDiscoverTargetDocument.Text(
                text("accountId"),
                SymbolTextScopeDocument.Workspace,
            ),
            SymbolDiscoverTargetDocument.Text(
                text("accountId"),
                SymbolTextScopeDocument.File(text("src/Controller.kt")),
            ),
        )

        targets.forEach { target ->
            val request = SymbolDiscoverRequest(target, count(25))
            val document = CanonicalOperationWireBindings.symbolDiscover
                .encodeRequest(request)
                .encodedDocument()
            val admitted = WireRequestEnvelope.admit(document).admittedRequest()

            assertEquals(
                WireDecoding.Decoded(request),
                CanonicalOperationWireBindings.symbolDiscover.decodeRequest(admitted),
            )
        }
    }

    @Test
    fun `structured discovery items round trip without parsing display text`() {
        val items = listOf(
            SymbolDiscoveryDocument.File(text("Controller.kt"), text("src/Controller.kt")),
            SymbolDiscoveryDocument.Declaration(
                candidateSelector = text("candidate:v1:payload"),
                kind = SymbolDiscoveryKindDocument.SYMBOL,
                name = text("handle"),
                file = text("src/Controller.kt"),
                offset = offset(27),
            ),
            SymbolDiscoveryDocument.TextMatch(
                query = text("accountId"),
                file = text("src/Controller.kt"),
                range = range(44, 53),
            ),
        )
        val result = SymbolDiscoverResult(BoundedProtocolList.create(items).refined())
        val outcome = OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.symbolDiscover.operation.id,
                EvidenceGeneration.parse(3).refined(),
                result,
            ),
        )
        val document = CanonicalOperationWireBindings.symbolDiscover
            .encodeOutcome(outcome)
            .encodedDocument()

        assertEquals(
            WireDecoding.Decoded(outcome),
            CanonicalOperationWireBindings.symbolDiscover.decodeOutcome(document),
        )
    }

    @Test
    fun `describe and relation share one structured symbol document`() {
        val symbol = SymbolDocument(
            selector = text("exact:v1:payload"),
            kind = SymbolKindDocument.FUNCTION,
            name = text("handle"),
            qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(
                text("sample.Controller.handle"),
            ),
            file = text("src/Controller.kt"),
            range = range(27, 61),
        )
        val describe = SymbolDescribeResult(symbol)
        val relation = RelationReadResult(
            BoundedProtocolList.create(listOf(symbol)).refined(),
        )

        val generation = EvidenceGeneration.parse(3).refined()
        val describeOutcome = OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.symbolDescribe.operation.id,
                generation,
                describe,
            ),
        )
        val relationOutcome = OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.relationRead.operation.id,
                generation,
                relation,
            ),
        )
        val describeDocument = CanonicalOperationWireBindings.symbolDescribe
            .encodeOutcome(describeOutcome)
            .encodedDocument()
        val relationDocument = CanonicalOperationWireBindings.relationRead
            .encodeOutcome(relationOutcome)
            .encodedDocument()

        assertEquals(
            WireDecoding.Decoded(describeOutcome),
            CanonicalOperationWireBindings.symbolDescribe.decodeOutcome(describeDocument),
        )
        assertEquals(
            WireDecoding.Decoded(relationOutcome),
            CanonicalOperationWireBindings.relationRead.decodeOutcome(relationDocument),
        )
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refined()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(offset(start), offset(end)).refined()

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error(failure.toString())
    }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest = when (this) {
        is WireRequestAdmission.Admitted -> request
        is WireRequestAdmission.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
