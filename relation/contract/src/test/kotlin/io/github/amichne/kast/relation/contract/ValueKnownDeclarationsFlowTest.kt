package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueKnownDeclarationsFlowTest {
    @Test
    fun `actual step sharing and rejected receipt keep the exact original known inputs`() {
        val f = KnownFlowCase()
        val step = f.step(f.known)
        assertSame(f.known, step.knownDeclarations)
        assertSame(f.known, step.receipts.single().knownDeclarations)
        assertSame(f.known, step.shareCallableEvidence(emptyList()).knownDeclarations)
        val request = ValueFlowRequest(f.source, f.domain.budget, f.domain.boundary, knownDeclarations = f.known)
        val rejected = ValueFlowWorkReceipt.rejected(request, RelationWorkCount.parse(1).flowRefined())
        assertSame(f.known, rejected.knownDeclarations)
        assertEquals(0, rejected.returnedResults.value)
        assertEquals(0L, rejected.returnedBytes.value)
        assertEquals(f.domain.boundary, rejected.domain.boundary)
    }

    @Test
    fun `append conserves equal full inputs but rejects a same-identity scope substitution`() {
        val f = KnownFlowCase()
        val first = f.step(f.known, ValueFlowTerminal.ResourceSuspended)
        val same = known(f.declaration)
        val appended = first.append(f.step(same)).flowRefined()
        assertSame(f.known, appended.knownDeclarations)
        assertSame(f.known, appended.receipts.first().knownDeclarations)
        assertSame(same, appended.receipts.last().knownDeclarations)
        val changed =
            RelationEndpoint.resolve(
                    f.declaration.lease,
                    (f.declaration.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
                    f.declaration.evidence,
                )
                .flowRefined() as RelationEndpoint.Resolved
        assertEquals(f.declaration.valueIdentity, changed.valueIdentity)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH),
            first.append(f.step(known(changed))),
        )
    }

    @Test
    fun `a foreign known authority cannot enter an otherwise valid compiler step`() {
        val f = KnownFlowCase()
        val foreign = flowEndpoint("Known", 300, 400, generation = 2) as RelationEndpoint.Resolved
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            ValueFlowStep.fromCompiler(
                f.source,
                emptyList(),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                f.domain,
                RelationWorkCount.parse(1).flowRefined(),
                knownDeclarations = known(foreign),
            ),
        )
    }
}

private class KnownFlowCase {
    val owner = flowEndpoint("owner", 0, 200)
    val source = ValueSite.fromCompiler(owner, flowRange(10, 20), ValueRole.LocalBinding).flowRefined()
    val declaration = flowEndpoint("Known", 300, 400) as RelationEndpoint.Resolved
    val known = known(declaration)
    val domain =
        RelationRequest.start(
            owner,
            RelationMeaning.References,
            implementationBudget(),
            RelationSearchBoundary.WORKSPACE_EXPANSION,
        )

    fun step(known: ValueKnownDeclarations, terminal: ValueFlowTerminal = ValueFlowTerminal.SupportedDomainExhausted) =
        ValueFlowStep.fromCompiler(
                source,
                emptyList(),
                emptyList(),
                terminal,
                domain,
                RelationWorkCount.parse(1).flowRefined(),
                knownDeclarations = known,
            )
            .flowRefined()
}

private fun known(endpoint: RelationEndpoint.Resolved): ValueKnownDeclarations {
    val current = RevalidatedRelationEndpoint.validate(endpoint, endpoint.evidence).flowRefined()
    return ValueKnownDeclarations.admit(listOf(current), endpoint.lease).flowRefined()
}
