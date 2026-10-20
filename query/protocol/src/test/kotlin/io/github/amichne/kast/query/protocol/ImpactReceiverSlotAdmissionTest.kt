package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationFailure
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactReceiverSlotAdmissionTest {
    @Test
    fun `requested receiver kind reaches the exact native revalidation claim`() {
        val f = ImpactPathProjectionFixture()
        val references = CanonicalQueryReferences()
        val selectors =
            listOf(f.owner, f.call.callable).map { endpoint ->
                val resolved = endpoint as RelationEndpoint.Resolved
                SymbolSelector.issue(resolved.lease, resolved.scope, resolved.evidence)
            }
        for ((kind, wire) in
            listOf(
                ValueReceiverKind.DISPATCH to ImpactReceiverKindDocument.DISPATCH,
                ValueReceiverKind.EXTENSION to ImpactReceiverKindDocument.EXTENSION,
            )) {
            val site = f.site(30, 35, ValueRole.Receiver(f.call, kind))
            val document = site.impactDocument(references).value()
            val request = document.siteRequest(selectors, budget()).value()
            assertEquals(kind, (request.role as ValueSiteRoleClaim.Receiver).kind)
            assertEquals(wire, kind.impactDocument())
        }
    }

    @Test
    fun `receiver mismatch preserves its exact failure and request correction`() {
        val code = ValueSiteRevalidationFailure.RECEIVER_KIND_MISMATCH.impactFailure()
        assertEquals(QueryImpactSourceFailureCode.RECEIVER_KIND_MISMATCH, code)
        val rejection =
            QueryRunRejection.ImpactSourceRejected(
                QueryImpactSourceFailureDocument.Admission(code, ProtocolOffset.parse(0).value())
            )
        assertEquals(ReadRecoveryAction.CORRECT_REQUEST, rejection.recoveryAction())
    }

    private fun budget() =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(20).value(),
                WorkUnitLimit.parse(100).value(),
                ElapsedTimeLimitMillis.parse(1000).value(),
            ),
            RelationByteLimit.parse(100000).value(),
        )
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
