package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImpactPeerReceiptBoundsTest {
    @Test
    fun `independent completed receipts exhaust work without wrapping into renewed permission`() {
        val f = QueryImpactPeerSourceAdmissionTest.Fixture()
        val model = f.document.models.values.single() as ImpactModelDocument.Boundary
        val rule = model.rules.values.first() as ImpactBoundaryRuleDocument.Continuation
        val second =
            rule.copy(
                id = ImpactModelIdentifierDocument.parse("second").value(),
                target =
                    rule.target.copy(
                        site =
                            rule.target.site.copy(
                                range =
                                    ImpactSourceRangeDocument(
                                        ProtocolOffset.parse(1).value(),
                                        ProtocolOffset.parse(2).value(),
                                    )
                            )
                    ),
            )
        val document = f.document.copy(models = bounded(listOf(model.copy(rules = bounded(listOf(rule, second))))))
        val peers = document.selectPeerSites(f.source.owner.lease).value()
        val work = Long.MAX_VALUE / 2 + 1
        val evidence =
            ImpactPeerSourceEvidence.admit(
                    peers,
                    listOf(
                        f.proof(0, 1, work, Long.MAX_VALUE),
                        f.proof(1, 2, work, Long.MAX_VALUE),
                    ),
                )
                .value()
        assertEquals(Long.MAX_VALUE, evidence.examinedWork)
    }

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
