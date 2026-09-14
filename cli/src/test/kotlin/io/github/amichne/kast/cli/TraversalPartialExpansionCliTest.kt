package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TraversalPartialExpansionCliTest {
    @Test
    fun `qualified CLI output preserves standalone partially expanded subject without claiming subtree count`() {
        val partial =
            TraversalPartialExpansionDocument.create(
                    ProtocolText.parse("exact:fixture-node").value(),
                    TraversalDepthDocument.parse(0).value(),
                    listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                    TraversalExpansionRemainderDocument.NOT_EXPLORED,
                )
                .value()
        val payload =
            TraversalRunResult(
                ProtocolText.parse("/workspace").value(),
                BoundedProtocolList.create(emptyList<TraversalRecordDocument>()).value(),
                partialExpansions = BoundedProtocolList.create(listOf(partial)).value(),
            )
        val outcome =
            OperationOutcome.Qualified(
                EvidenceEnvelope(
                    CanonicalOperation.TRAVERSAL_RUN.id,
                    EvidenceBasis.Published(EvidenceGeneration.parse(1).value()),
                    payload,
                ),
                TraversalRunQualification.terminalIncomplete(
                        listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
                        listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                    )
                    .value(),
            )
        val projected = CanonicalReadCliDocuments.projectTraversal(outcome) as ProjectedCliOutcome.Qualified
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        assertEquals("qualified", document.getValue("status").jsonPrimitive.content)
        val entry = document.getValue("partialExpansions").jsonArray.single().jsonObject
        assertEquals(setOf("subject", "depth", "limitations", "remainder", "scope"), entry.keys)
        assertEquals("exact:fixture-node", entry.getValue("subject").jsonPrimitive.content)
        assertEquals("0", entry.getValue("depth").jsonPrimitive.content)
        assertEquals("result-limit-reached", entry.getValue("limitations").jsonArray.single().jsonPrimitive.content)
        assertEquals("not_explored", entry.getValue("remainder").jsonPrimitive.content)
        assertEquals("page", entry.getValue("scope").jsonPrimitive.content)
    }

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}
