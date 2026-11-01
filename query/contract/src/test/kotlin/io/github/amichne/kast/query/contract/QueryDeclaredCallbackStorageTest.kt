package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryDeclaredCallbackStorageTest {
    @Test
    fun `declared owner result condition and operation charge retained proof and preserve basis`() {
        val f = QueryImpactLedgerFixture()
        val compact = transfer(f, "fixture.Input")
        val expanded = transfer(f, "fixture." + "X".repeat(1200))
        assertTrue(
            QueryImpactRetainedGraph().site(expanded.source) > QueryImpactRetainedGraph().site(compact.source) + 2000
        )
        assertTrue(
            QueryImpactRetainedGraph().site(expanded.target) > QueryImpactRetainedGraph().site(compact.target) + 2000
        )
        assertTrue(
            expanded.target.identity.storageBytes(QueryImpactRetainedGraph()) >
                compact.target.identity.storageBytes(QueryImpactRetainedGraph()) + 2000
        )
        val graph = QueryImpactRetainedGraph()
        compact.target.identity.storageBytes(graph)
        assertEquals(8L, compact.target.conditions.storageBytes(graph))
        assertEquals(8L, compact.target.identity.storageBytes(graph))
        val operation = (compact.evidence as ValueSourceTransfer).operation
        assertTrue(operation.storageBytes(QueryImpactRetainedGraph()) > 8192)
        assertFalse(compact.target.hasForeignBasis(f.lease))
        assertTrue(compact.target.hasForeignBasis(QueryImpactLedgerFixture(generation = 2).lease))
        val arrival = f.destination.withState(conditions = compact.target.conditions).declaredStorage()
        assertFalse(arrival.hasForeignBasis(f.lease))
        assertEquals(compact.source.range, arrival.conditions.facts.single().origin.range)
        assertTrue(arrival.hasForeignBasis(QueryImpactLedgerFixture(generation = 2).lease))
    }

    private fun transfer(f: QueryImpactLedgerFixture, parameterType: String): ValueTransfer {
        val bodyRange = range(40, 90)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(f.owner.file, bodyRange),
                    null,
                    emptyList(),
                    listOf(parameterType),
                    0,
                )
                .declaredStorage() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(f.owner.file, bodyRange, signature).declaredStorage()
        val contract =
            CallbackDependencyContract.fromCompiler(
                    f.lease.identity,
                    RelationOccurrence.fromBoundary(f.owner.file, 30, 100).declaredStorage(),
                    RelationCallableBody.Named.fromCompiler(f.evidence).declaredStorage(),
                    binaryTarget(),
                    ValueArgumentPosition.parse(0).declaredStorage(),
                    DependencyClassDigest.parse("ab".repeat(32)).declaredStorage(),
                    CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    CallbackDependencyInvocationKind.AT_MOST_ONCE,
                )
                .declaredStorage()
        val binding =
            DeclaredCallbackValueBinding.admit(f.owner, body, contract, DeclaredCallbackResultEffect.RETURNS_RESULT_OF)
                .declaredStorage()
        val source =
            ValueSite.fromCompiler(
                    f.owner,
                    range(50, 60),
                    ValueRole.Return,
                    ValueExecutionOwner.declaredCallback(binding),
                )
                .declaredStorage()
        return ValueTransfer.declaredCallbackResult(source, binding).declaredStorage()
    }

    private fun binaryTarget(): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///effects.jar!/fixture/EffectsKt.class").declaredStorage()
                ),
                1000,
                1100,
                "select",
                "fixture.select",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.select", null, emptyList(), listOf("(T)->R"), 2)
                    .declaredStorage(),
            )
            .declaredStorage()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).declaredStorage()
}

private fun <T, F> Refinement<T, F>.declaredStorage(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid declared storage fixture: $failure")
    }
