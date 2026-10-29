package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationFailure
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.SymbolSelector
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

internal suspend fun Project.assertRestoredTupleSites(
    fixture: NativePacketValueFixture,
    enclosing: SymbolSelector,
    transfers: List<ReviewedValueTransfer>,
) {
    val ordinary = transfers.filter { it.binding is ReviewedCallValueBinding }
    assertEquals("Each case must reach tuple construction and its exact property projection", 2, ordinary.size)
    for (transfer in ordinary) {
        val selected = SymbolSelector.issue(fixture.lease, transfer.source.enclosing.scope, transfer.binding.target)
        val claims = tupleSiteClaims(transfer, selected)
        val adapter = IntellijValueFlowCompilerAdapter()
        val wrong =
            ValueSiteRevalidationRequest.create(
                    enclosing = enclosing,
                    anchor = transfer.source.range,
                    role = claims.negative,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val rejected =
            adapter.revalidateSite(project = this, current = fixture.lease, request = wrong, model = fixture.model)
        assertEquals(claims.rejection, rejected)
        val expected =
            ValueSiteRevalidationRequest.create(
                    enclosing = enclosing,
                    anchor = transfer.source.range,
                    role = claims.positive,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val restored =
            adapter.revalidateSite(project = this, current = fixture.lease, request = expected, model = fixture.model)
        assertTrue("The exact emitted tuple role must restore: $restored", restored is ValueModelSiteRead.Revalidated)
        val site = (restored as ValueModelSiteRead.Revalidated).position.site
        assertEquals(transfer.source.role, site.role)
        assertEquals(transfer.source.range, site.range)
        assertEquals(transfer.source.enclosing.valueIdentity, site.enclosing.valueIdentity)
        assertEquals(transfer.source.enclosing.scope, site.enclosing.scope)
        assertEquals(transfer.source.basis, site.basis)
        assertEquals(transfer.source.executionOwner.identity, site.executionOwner.identity)
    }
}

private fun tupleSiteClaims(transfer: ReviewedValueTransfer, selected: SymbolSelector): TupleSiteClaims =
    when (val role = transfer.source.role) {
        is ValueRole.Receiver ->
            TupleSiteClaims(
                positive = ValueSiteRoleClaim.Receiver(role.call.range, selected, role.kind),
                negative =
                    ValueSiteRoleClaim.Receiver(
                        role.call.range,
                        selected,
                        if (role.kind == ValueReceiverKind.EXTENSION) ValueReceiverKind.DISPATCH
                        else ValueReceiverKind.EXTENSION,
                    ),
                rejection = ValueModelSiteRead.ContractRejected(ValueSiteRevalidationFailure.RECEIVER_KIND_MISMATCH),
            )
        is ValueRole.Argument -> {
            val positive = ValueSiteRoleClaim.Argument(role.call.range, selected, role.position)
            if ((role.call.signature as CanonicalCompilerCallableSignature).valueParameters.size == 2)
                TupleSiteClaims(
                    positive = positive,
                    negative =
                        ValueSiteRoleClaim.Argument(
                            role.call.range,
                            selected,
                            ValueArgumentPosition.parse(1 - role.position.value).nativeRefined(),
                        ),
                    rejection =
                        ValueModelSiteRead.ContractRejected(ValueSiteRevalidationFailure.ARGUMENT_POSITION_MISMATCH),
                )
            else
                TupleSiteClaims(
                    positive = positive,
                    negative = ValueSiteRoleClaim.Receiver(role.call.range, selected, ValueReceiverKind.EXTENSION),
                    rejection = ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                )
        }
        else -> error("Fixture prerequisite: ordinary tuple transfer needs a receiver or argument role")
    }

private data class TupleSiteClaims(
    val positive: ValueSiteRoleClaim,
    val negative: ValueSiteRoleClaim,
    val rejection: ValueModelSiteRead,
)
