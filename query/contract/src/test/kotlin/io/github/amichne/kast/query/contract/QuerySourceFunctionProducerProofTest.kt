package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Direct producer admission must conserve full site proof independently of public seed readmission. */
class QuerySourceFunctionProducerProofTest {
    @Test
    fun `the actual source function result conserves its full origin`() {
        val f = QuerySourceFunctionProofCase()
        val producer = QueryImpactProducer.admit(f.call.resultSite(), f.origin).sourceProof()
        assertSame(f.origin, producer.origin)
        assertSame(f.call, producer.invocation)
    }

    @Test
    fun `same site identity cannot replace the enclosing scope proof`() {
        val f = QuerySourceFunctionProofCase()
        val changed =
            ValueSite.fromCompiler(
                    f.alternate(),
                    f.call.range,
                    ValueRole.ExpressionResult,
                    executionOwner = f.call.executionOwner,
                )
                .sourceProof()
        assertEquals(f.call.resultSite(), changed)
        assertEquals(
            Refinement.Rejected(QueryImpactProducerFailure.NOT_INVOCATION_RESULT),
            QueryImpactProducer.admit(changed, f.origin),
        )
    }

    @Test
    fun `same site identity cannot replace the execution enclosing proof`() {
        val f = QuerySourceFunctionProofCase()
        val changed =
            ValueSite.fromCompiler(
                    f.base.owner,
                    f.call.range,
                    ValueRole.ExpressionResult,
                    executionOwner = ValueExecutionOwner.declaration(f.alternate()),
                )
                .sourceProof()
        assertEquals(f.call.resultSite(), changed)
        assertEquals(
            Refinement.Rejected(QueryImpactProducerFailure.NOT_INVOCATION_RESULT),
            QueryImpactProducer.admit(changed, f.origin),
        )
    }
}

private class QuerySourceFunctionProofCase {
    val base = QueryImpactLedgerFixture()
    val call = ValueFunctionInvocation.fromCompiler(base.owner, range(50, 65), builtin()).sourceProof()
    private val property = property()
    val origin =
        ValueProducerOrigin.SourceFunction.fromCompiler(
                call,
                RevalidatedRelationEndpoint.validate(property, property.evidence).sourceProof(),
                RelationOccurrence.fromBoundary(base.file, 50, 55).sourceProof(),
            )
            .sourceProof()

    fun alternate() =
        RelationEndpoint.resolve(
                base.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                base.evidence,
            )
            .sourceProof()

    private fun property(): RelationEndpoint.Resolved {
        val signature =
            CanonicalCompilerSignature.property("fixture.adapt", null, emptyList(), "()->String").sourceProof()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    base.file,
                    210,
                    250,
                    "adapt",
                    "fixture.adapt",
                    CompilerSymbolKind.PROPERTY,
                    signature,
                )
                .sourceProof()
        return RelationEndpoint.resolve(base.lease, base.scope, evidence).sourceProof()
    }

    private fun builtin() =
        SourceLessCallable.fromCompiler(
                CanonicalCompilerSignature.function("kotlin.Function0.invoke", null, emptyList(), emptyList(), 0)
                    .sourceProof(),
                CompilerSymbolKind.FUNCTION,
                SourceLessCallableOrigin.LIBRARY,
                SourceLessCallableModuleKind.BUILTINS,
                SourceLessCallableModuleName.parse("kotlin-stdlib").sourceProof(),
            )
            .sourceProof()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).sourceProof()
}

private fun <V, F> Refinement<V, F>.sourceProof(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Source-function producer prerequisite rejected: $failure")
    }
