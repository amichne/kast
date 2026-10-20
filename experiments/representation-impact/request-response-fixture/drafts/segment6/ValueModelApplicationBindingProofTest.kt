package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

/** Draft controls for full binding proof admission, outside the active source set. */
class ValueModelApplicationBindingProofTest {
    @Test
    fun `receiver cannot borrow a binding with another enclosing proof at the same call identity`() {
        val f = ReviewedModelOracleCase()
        val alternate = alternateHost(f)
        val call = ValueInvocation.fromCompiler(alternate, f.invocation.range, f.callable, f.execution).flowRefined()
        assertEquals(f.invocation, call)
        assertNotEquals(f.invocation.enclosing, call.enclosing)
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.CollectionSelection.selectFirst(f.receiver(), binding(f, call)),
        )
    }

    @Test
    fun `receiver cannot borrow a binding with another execution host proof at the same call identity`() {
        val f = ReviewedModelOracleCase()
        val body = (f.execution.body as ValueExecutionBody.Anonymous).callable
        val execution = ValueExecutionOwner.fromCompiler(alternateHost(f), body, f.host.lease.identity).flowRefined()
        val call = ValueInvocation.fromCompiler(f.host, f.invocation.range, f.callable, execution).flowRefined()
        assertEquals(f.invocation, call)
        assertNotEquals(f.invocation.executionOwner.enclosing, call.executionOwner.enclosing)
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.CollectionSelection.selectFirst(f.receiver(), binding(f, call)),
        )
    }

    @Test
    fun `equal full proof reconstructed as distinct objects remains admissible`() {
        val f = ReviewedModelOracleCase()
        val host = RelationEndpoint.resolve(f.host.lease, f.host.scope, f.host.evidence, f.host.constraints).flowRefined()
        val body = (f.execution.body as ValueExecutionBody.Anonymous).callable
        val execution = ValueExecutionOwner.fromCompiler(host, body, f.host.lease.identity).flowRefined()
        val call = ValueInvocation.fromCompiler(host, f.invocation.range, f.callable, execution).flowRefined()
        assertNotSame(f.host, host)
        assertEquals(f.host, host)
        assertEquals(f.binding(), binding(f, call))
        assertEquals(
            f.receiver(),
            ValueModelApplication.CollectionSelection.selectFirst(f.receiver(), binding(f, call)).flowRefined().source,
        )
    }

    private fun alternateHost(f: ReviewedModelOracleCase) =
        RelationEndpoint.resolve(
            f.host.lease,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.INCLUDE,
            ),
            f.host.evidence,
        ).flowRefined()

    private fun binding(f: ReviewedModelOracleCase, call: ValueInvocation) =
        ReviewedLibraryBinding.fromCompiler(call, ReviewedValueModel.FirstOrNull, f.artifact).flowRefined()
}
