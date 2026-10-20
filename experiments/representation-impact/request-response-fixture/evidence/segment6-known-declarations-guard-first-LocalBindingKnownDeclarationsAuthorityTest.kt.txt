package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LocalBindingKnownDeclarationsAuthorityTest {
    @Test
    fun `foreign explicit input rejects before every traversal effect and preserves its raw receipt`() {
        val f = KnownScanCase(generation = 2)
        val rejected = assertInstanceOf(ValueFlowRead.ContractRejected::class.java, f.read())
        assertEquals(ValueFlowStepFailure.BASIS_MISMATCH, rejected.cause)
        assertEquals(1L, rejected.examinedWorkUnits.value)
        assertEquals(emptyList<String>(), f.effects)
        val receipt = ValueFlowWorkReceipt.rejected(f.request, rejected.examinedWorkUnits)
        assertSame(f.known, receipt.knownDeclarations)
        assertEquals(f.request.boundary, receipt.domain.boundary)
        assertEquals(1L, receipt.examinedWorkUnits.value)
        assertEquals(0, receipt.returnedResults.value)
        assertEquals(0L, receipt.returnedBytes.value)
    }

    @Test
    fun `current explicit input permits the same scanner and keeps its original question`() {
        val f = KnownScanCase(generation = 1)
        val observed = assertInstanceOf(ValueFlowRead.Observed::class.java, f.read())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, observed.step.terminal)
        assertEquals(1, f.effects.count { it == "enumerate" })
        assertSame(f.known, observed.step.knownDeclarations)
        assertSame(f.known, observed.step.receipts.single().knownDeclarations)
        assertEquals(2L, observed.step.examinedWorkUnits.value)
    }
}

private class KnownScanCase(generation: Long) {
    private val owner = flowEndpoint("owner", 0, 200)
    private val source = ValueSite.fromCompiler(owner, flowRange(10, 20), ValueRole.LocalBinding).flowRefined()
    private val declaration = flowEndpoint("Known", 300, 400, generation = generation) as RelationEndpoint.Resolved
    private val current = RevalidatedRelationEndpoint.validate(declaration, declaration.evidence).flowRefined()
    val known = ValueKnownDeclarations.admit(listOf(current), declaration.lease).flowRefined()
    val request =
        ValueFlowRequest(
            source,
            implementationBudget(),
            RelationSearchBoundary.WORKSPACE_EXPANSION,
            knownDeclarations = known,
        )
    val effects = mutableListOf<String>()

    fun read(): ValueFlowRead =
        LocalBindingReferenceScan<Int>(
                request = request,
                domain = RelationRequest.start(owner, RelationMeaning.References, request.budget, request.boundary),
                enumerate = { effects += "enumerate" },
                key = { error("Candidate-free authority oracle requested an address") },
                confirm = { error("Candidate-free authority oracle confirmed a candidate") },
                checkCanceled = { effects += "cancellation" },
                elapsedMillis = {
                    effects += "deadline"
                    0L
                },
                onWork = { effects += "work" },
                admitRole = {
                    effects += "role"
                    Refinement.Refined(Unit)
                },
            )
            .read()
}
