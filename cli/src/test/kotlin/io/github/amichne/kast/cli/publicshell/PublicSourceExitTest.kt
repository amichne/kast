package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.LiveReadOutputSchemaTest
import io.github.amichne.kast.cli.rpc.QueryDeliveryFixtures
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicSourceExitTest {
    @Test
    fun `detail marker without detail cannot claim delivery`() {
        val reply = QueryDeliveryFixtures.presentationHeaders().first() as ToolRpcReply.Complete
        val header =
            Json.decodeFromJsonElement<QueryPresentationHeaderDocument>(reply.document)
                .copy(deliveryStop = QueryPresentationHeaderStop.DETAIL_SECTION, detail = null)
        val projected = header.project() as ProjectedOperationOutcome.Complete
        val admitted = presentToolRpcExit("query_symbols", CliExit.Complete(projected.document))
        assertEquals(11, classifyPublicReply(admitted).code)
    }

    @Test
    fun `source elapsed qualification needs explicit caller grant for expiry exit`() {
        val basis = EvidenceBasis.Published(refined(EvidenceGeneration.parse(1)))
        val result = LiveReadOutputSchemaTest().sourceResult(basis)
        val qualification =
            refined(
                SourceReadQualification.create(
                    refined(SourceEntityCountDocument.parse(0)),
                    listOf(SourceReadLimitationDocument.TIME_LIMIT_REACHED),
                    SourceQualifiedProgressDocument.TerminalIncomplete(
                        SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE
                    ),
                )
            )
        val projected =
            CanonicalSourceReadCliDocuments.project(
                OperationOutcome.Qualified(
                    EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, result),
                    qualification,
                )
            ) as ProjectedOperationOutcome.Qualified
        val admitted = presentToolRpcExit("query_symbols", CliExit.Qualified(projected.document))
        assertEquals(124, classifyPublicReply(admitted, PublicElapsedGrant.EXPLICIT).code)
        assertEquals(10, classifyPublicReply(admitted, PublicElapsedGrant.ABSENT).code)
    }

    private fun <T, E> refined(value: Refinement<T, E>): T =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid independent fixture: ${value.failure}")
        }
}
