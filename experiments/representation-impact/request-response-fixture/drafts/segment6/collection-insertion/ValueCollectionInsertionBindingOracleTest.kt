package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class ValueCollectionInsertionBindingOracleTest {
    @Test
    fun `another occurrence of the same library callable cannot borrow the binding`() {
        val case = SingleListModelOracleCase()
        val other = case.argument(call = case.call(start = 45))
        assertEquals(case.argument().range, other.range)
        reject(case, source = other)
    }

    @Test
    fun `a stale actual argument cannot borrow a fresh library binding`() {
        val case = SingleListModelOracleCase()
        val stale = SingleListModelOracleCase(generation = 2)
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH),
            ValueModelApplication.CollectionInsertion.insertSingle(stale.argument(), case.binding()),
        )
    }

    @Test
    fun `a changed full enclosing scope cannot borrow the argument binding`() {
        val case = SingleListModelOracleCase()
        val host = changedScope(case.host)
        val call = case.call(enclosing = host)
        assertEquals(case.invocation, call)
        assertNotEquals(case.invocation.enclosing, call.enclosing)
        reject(case, binding = case.binding(call))
    }

    @Test
    fun `a changed full callable scope cannot borrow the argument binding`() {
        val case = SingleListModelOracleCase()
        val callable = changedScope(case.callable as RelationEndpoint.Resolved)
        val call = case.call(callable = callable)
        assertEquals(case.invocation, call)
        assertNotEquals(case.invocation.callable, call.callable)
        reject(case, binding = case.binding(call))
    }

    @Test
    fun `a changed declaration constraint cannot borrow an unrestricted binding`() {
        val case = SingleListModelOracleCase()
        val kinds = SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.FUNCTION)).flowRefined()
        val host =
            RelationEndpoint.resolve(
                    lease = case.host.lease,
                    scope = case.host.scope,
                    evidence = case.host.evidence,
                    constraints = SymbolDiscoveryConstraints.None.copy(declarationKinds = kinds),
                )
                .flowRefined()
        val call = case.call(enclosing = host)
        assertEquals(case.invocation, call)
        assertNotEquals(case.invocation.enclosing.constraints, call.enclosing.constraints)
        reject(case, binding = case.binding(call))
    }

    @Test
    fun `a changed full execution host cannot borrow the argument binding`() {
        val case = SingleListModelOracleCase()
        val body = (case.execution.body as ValueExecutionBody.Anonymous).callable
        val execution =
            ValueExecutionOwner.fromCompiler(changedScope(case.host), body, case.host.lease.identity).flowRefined()
        val call = case.call(execution = execution)
        assertEquals(case.invocation, call)
        assertNotEquals(case.execution.enclosing, execution.enclosing)
        reject(case, binding = case.binding(call))
    }

    @Test
    fun `equal full proofs recreated as fresh objects keep the exact insertion`() {
        val case = SingleListModelOracleCase()
        val host =
            RelationEndpoint.resolve(
                    lease = case.host.lease,
                    scope = case.host.scope,
                    evidence = case.host.evidence,
                    constraints = case.host.constraints,
                )
                .flowRefined()
        val body = (case.execution.body as ValueExecutionBody.Anonymous).callable
        val execution = ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).flowRefined()
        val call = case.call(enclosing = host, execution = execution)
        assertNotSame(case.host, host)
        assertEquals(case.host, host)
        val inserted =
            ValueModelApplication.CollectionInsertion.insertSingle(case.argument(), case.binding(call)).flowRefined()
        assertEquals(case.insert().target, inserted.target)
    }

    private fun changedScope(endpoint: RelationEndpoint.Resolved): RelationEndpoint.Resolved {
        val scope = endpoint.scope as SymbolSearchScope.Workspace
        val libraryPolicy =
            if (scope.libraries == SymbolLibraryPolicy.INCLUDE) SymbolLibraryPolicy.EXCLUDE
            else SymbolLibraryPolicy.INCLUDE
        return RelationEndpoint.resolve(
                lease = endpoint.lease,
                scope = scope.copy(libraries = libraryPolicy),
                evidence = endpoint.evidence,
                constraints = endpoint.constraints,
            )
            .flowRefined()
    }

    private fun reject(
        case: SingleListModelOracleCase,
        source: ValueSite = case.argument(),
        binding: ReviewedLibraryBinding = case.binding(),
    ) {
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.CollectionInsertion.insertSingle(source, binding),
        )
    }
}
