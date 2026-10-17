package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackProjectionBasisWireTest {
    private val fixture = QueryCallbackWireFixture()
    private val foreign =
        ImpactSemanticBasisDocument.Published(
            fixture.text("/another-workspace"),
            (ImpactEvidenceRevisionDocument.parse(2L) as Refinement.Refined).value,
        )

    @Test
    fun `unavailable callback rejects a substituted projection basis`() {
        rejectCallbackBasis(QueryCallbackFlowWireDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK))
    }

    @Test
    fun `rejected callback rejects a substituted projection basis`() {
        rejectCallbackBasis(
            QueryCallbackFlowWireDocument.ContractRejected(QueryCallbackFlowFailureDocument.BASIS_MISMATCH)
        )
    }

    private fun rejectCallbackBasis(flow: QueryCallbackFlowWireDocument) {
        val wire = fixture.callbackDocument().toWireDocument().copy(flow = flow)
        val raw = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire)
        val decoded = wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), raw)
        assertTrue(decoded.toContract() is WireDocumentConversion.Converted)
        val projection = wire.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val forged = wire.copy(resultProjection = projection.copy(basis = foreign))
        val forgedRaw = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), forged)
        assertEquals(
            WireDocumentConversion.Rejected,
            wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), forgedRaw).toContract(),
        )
    }

    @Test
    fun `unproven membership still binds the available callback basis`() {
        val wire =
            fixture
                .callbackDocument()
                .toWireDocument()
                .copy(
                    resultProjection =
                        QueryCallbackResultProjectionWireDocument.Unproven(
                            QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
                        )
                )
        assertTrue(wire.toContract() is WireDocumentConversion.Converted)
        assertEquals(WireDocumentConversion.Rejected, wire.copy(basis = foreign).toContract())
    }

    @Test
    fun `callback frame basis is a required encoded field`() {
        val wire = fixture.callbackDocument().toWireDocument()
        val raw = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire)
        assertTrue(raw.contains(",\"basis\":"))
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackObservationWireDocument.serializer(),
                raw.substringBeforeLast(",\"basis\":") + "}",
            )
        }
    }

    @Test
    fun `unavailable named reference rejects a substituted projection basis`() {
        val callback = fixture.callbackDocument().toWireDocument()
        val reference =
            QueryNamedCallbackReferenceWireDocument(
                callback.target,
                QueryCallbackReferenceReceiverWireDocument.Absent,
                QueryCallbackReferenceReceiverWireDocument.Unbound,
                QueryNamedCallbackReferenceFlowWireDocument.Unavailable(
                    (QueryCallbackGraphObligationsDocument.from(listOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK))
                            as Refinement.Refined)
                        .value
                ),
            )
        val target = QueryCallableTargetWireDocument.NamedReference(reference, callback.resultProjection)
        val wire =
            QueryCallableObservationWireDocument(
                callback.callbackBody,
                callback.lexicalOwner,
                QueryCallbackBodyWireDocument.Named(callback.lexicalOwner),
                target,
                callback.basis,
            )
        assertTrue(wire.toContract() is WireDocumentConversion.Converted)
        val projection = target.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val forged = wire.copy(target = target.copy(resultProjection = projection.copy(basis = foreign)))
        val raw = wireJson.encodeToString(QueryCallableObservationWireDocument.serializer(), forged)
        assertEquals(
            WireDocumentConversion.Rejected,
            wireJson.decodeFromString(QueryCallableObservationWireDocument.serializer(), raw).toContract(),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallableObservationWireDocument.serializer(),
                raw.substringBeforeLast(",\"basis\":") + "}",
            )
        }
    }
}
