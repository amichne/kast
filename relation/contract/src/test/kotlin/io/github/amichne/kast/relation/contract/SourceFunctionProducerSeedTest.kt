package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Assumed native SOURCE/VAL facts exercise structural admission, not K2 origin or mutability. */
class SourceFunctionProducerSeedTest {
    @Test
    fun `source function seed keeps the actual builtin property reference and selected anchor`() {
        val f = SourceFunctionSeedCase()
        val selection = ValueInvocationSelection.fromCompiler(f.invocation, flowRange(25, 35)).flowRefined()
        val request = f.request(anchor = selection.selectedRange)
        val seed =
            ValueProducerSeed.fromSourceFunction(request, f.invocation.resultSite(), f.origin, selection).flowRefined()
        assertSame(f.origin, seed.origin)
        assertSame(f.invocation, seed.invocation)
        val origin = seed.origin as ValueProducerOrigin.SourceFunction
        assertSame(f.property, origin.property.endpoint)
        assertEquals(f.reference, origin.reference)
        assertEquals("kotlin.Function0.invoke", origin.invocation.signature.qualifiedIdentity.value)
        assertEquals(f.invocation.resultSite(), seed.site)
        assertSame(seed.origin, seed.readmit(request).flowRefined().origin)
        assertTrue(seed.retainedBytes > seed.site.retainedBytes)
    }

    @Test
    fun `source function readmission rejects another expected property or source method`() {
        val f = SourceFunctionSeedCase()
        val seed = f.seed()
        val other = flowEndpoint("otherAdapt", 260, 290, propertyType = "()->String") as RelationEndpoint.Resolved
        for (expected in listOf(other, f.host)) {
            assertEquals(
                Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH),
                seed.readmit(f.request(expected)),
            )
        }
    }

    @Test
    fun `source function seed cannot substitute another invocation selection or receiver range`() {
        val f = SourceFunctionSeedCase()
        val other = ValueFunctionInvocation.fromCompiler(f.host, flowRange(40, 55), f.invocation.callable).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH),
            ValueProducerSeed.fromSourceFunction(
                f.request(),
                f.invocation.resultSite(),
                f.origin,
                ValueInvocationSelection.exact(other),
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH),
            ValueProducerOrigin.SourceFunction.fromCompiler(other, f.proof, f.reference),
        )
    }

    @Test
    fun `source function origin rejects a foreign basis and nonproperty declaration`() {
        val f = SourceFunctionSeedCase()
        val foreign =
            flowEndpoint("adapt", 210, 250, generation = 2, propertyType = "()->String") as RelationEndpoint.Resolved
        assertEquals(
            Refinement.Rejected(ValueProducerSeedFailure.BASIS_MISMATCH),
            ValueProducerOrigin.SourceFunction.fromCompiler(
                f.invocation,
                RevalidatedRelationEndpoint.validate(foreign, foreign.evidence).flowRefined(),
                f.reference,
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH),
            ValueProducerOrigin.SourceFunction.fromCompiler(
                f.invocation,
                RevalidatedRelationEndpoint.validate(f.host, f.host.evidence).flowRefined(),
                f.reference,
            ),
        )
    }

    @Test
    fun `ordinary invoke identity cannot replace full enclosing or execution proof`() {
        val f = SourceFunctionSeedCase()
        val alternate = alternateHost(f.host)
        val wrongHost =
            ValueFunctionInvocation.fromCompiler(alternate, f.invocation.range, f.invocation.callable).flowRefined()
        val wrongExecution =
            ValueFunctionInvocation.fromCompiler(
                    f.host,
                    f.invocation.range,
                    f.invocation.callable,
                    ValueExecutionOwner.declaration(alternate),
                )
                .flowRefined()
        for ((call, failure) in
            listOf(
                wrongHost to ValueProducerSeedFailure.ENCLOSING_MISMATCH,
                wrongExecution to ValueProducerSeedFailure.EXECUTION_OWNER_MISMATCH,
            )) {
            assertEquals(f.invocation, call)
            val origin = ValueProducerOrigin.SourceFunction.fromCompiler(call, f.proof, f.reference).flowRefined()
            assertEquals(
                Refinement.Rejected(failure),
                ValueProducerSeed.fromSourceFunction(f.request(), call.resultSite(), origin),
            )
        }
        assertEquals(
            Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH),
            ValueProducerSeed.fromSourceFunction(
                f.request(),
                f.invocation.resultSite(),
                f.origin,
                ValueInvocationSelection.exact(wrongHost),
            ),
        )
    }

    @Test
    fun `property scope is part of the admitted source function proof`() {
        val f = SourceFunctionSeedCase()
        val alternate = alternateHost(f.property)
        val proof = RevalidatedRelationEndpoint.validate(alternate, alternate.evidence).flowRefined()
        val origin = ValueProducerOrigin.SourceFunction.fromCompiler(f.invocation, proof, f.reference).flowRefined()
        assertEquals(f.property.valueIdentity, alternate.valueIdentity)
        assertEquals(
            Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH),
            ValueProducerSeed.fromSourceFunction(f.request(), f.invocation.resultSite(), origin),
        )
    }

    @Test
    fun `equal full endpoint proofs remain admissible as separate objects`() {
        val f = SourceFunctionSeedCase()
        val host =
            RelationEndpoint.resolve(f.host.lease, f.host.scope, f.host.evidence, f.host.constraints).flowRefined()
        val call = ValueFunctionInvocation.fromCompiler(host, f.invocation.range, f.invocation.callable).flowRefined()
        val origin = ValueProducerOrigin.SourceFunction.fromCompiler(call, f.proof, f.reference).flowRefined()
        assertEquals(f.invocation, call)
        val seed = ValueProducerSeed.fromSourceFunction(f.request(), call.resultSite(), origin).flowRefined()
        assertSame(origin, seed.readmit(f.request()).flowRefined().origin)
    }

    private fun alternateHost(endpoint: RelationEndpoint.Resolved) =
        RelationEndpoint.resolve(
                endpoint.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                endpoint.evidence,
                endpoint.constraints,
            )
            .flowRefined()

    @Test
    fun `declared producer factory keeps its original typed origin`() {
        val f = SourceFunctionSeedCase()
        val call = ValueInvocation.fromCompiler(f.host, f.invocation.range, f.host).flowRefined()
        val seed = ValueProducerSeed.fromCompiler(f.request(f.host), call.resultSite(), call).flowRefined()
        assertSame(call, (seed.origin as ValueProducerOrigin.Declared).invocation)
        assertSame(call, seed.invocation)
    }
}

private class SourceFunctionSeedCase {
    val host = flowEndpoint("execute", 0, 200) as RelationEndpoint.Resolved
    val property = flowEndpoint("adapt", 210, 250, propertyType = "()->String") as RelationEndpoint.Resolved
    val proof = RevalidatedRelationEndpoint.validate(property, property.evidence).flowRefined()
    val invocation = ValueFunctionInvocation.fromCompiler(host, flowRange(20, 35), builtin()).flowRefined()
    val reference = RelationOccurrence.fromBoundary(host.file, 20, 25).flowRefined()
    val origin = ValueProducerOrigin.SourceFunction.fromCompiler(invocation, proof, reference).flowRefined()

    fun seed() = ValueProducerSeed.fromSourceFunction(request(), invocation.resultSite(), origin).flowRefined()

    fun request(
        expected: RelationEndpoint.Resolved = property,
        anchor: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange = invocation.range,
    ) =
        ValueProducerSeedRequest.create(
                SymbolSelector.issue(host.lease, host.scope, host.evidence),
                anchor,
                SymbolSelector.issue(expected.lease, expected.scope, expected.evidence),
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(32).flowRefined(),
                        WorkUnitLimit.parse(128).flowRefined(),
                        ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                    ),
                    RelationByteLimit.parse(1_000_000).flowRefined(),
                ),
                RelationSearchBoundary.RETAINED_SUBJECT,
            )
            .flowRefined()

    private fun builtin() =
        SourceLessCallable.fromCompiler(
                CanonicalCompilerSignature.function("kotlin.Function0.invoke", null, emptyList(), emptyList(), 0)
                    .flowRefined(),
                CompilerSymbolKind.FUNCTION,
                SourceLessCallableOrigin.LIBRARY,
                SourceLessCallableModuleKind.BUILTINS,
                SourceLessCallableModuleName.parse("kotlin-stdlib").flowRefined(),
            )
            .flowRefined()
}
