package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Frozen precision controls for actual metadata and physically distinct execution-owner proofs. */
class ValueModeledApplicationPhysicalStorageTest {
    @Test
    fun `actual module text pays two bytes per extra character`() {
        val f = ReviewedModelOracleCase()
        val source = f.receiver()
        val original = application(source, f.invocation, f.artifact)
        val longerArtifact = f.artifact.copy(moduleName = LibraryModelModuleName.parse("packet-stdlib-longer").flowRefined())
        val longer = application(source, f.invocation, longerArtifact)
        val delta = longerArtifact.moduleName.value.length - f.artifact.moduleName.value.length
        assertNotEquals(original.binding, longer.binding)
        assertEquals(2L * delta, bytes(f, source, longer) - bytes(f, source, original))
    }

    @Test
    fun `an execution host with the same value identity keeps its full distinct endpoint payload`() {
        val f = ReviewedModelOracleCase()
        val host = RelationEndpoint.resolve(f.host.lease,
            SymbolSearchScope.Workspace(SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE, SymbolLibraryPolicy.INCLUDE), f.host.evidence).flowRefined()
        val execution = ValueExecutionOwner.declaration(host)
        val call = ValueInvocation.fromCompiler(f.host, f.invocation.range, f.callable, execution).flowRefined()
        val originalCall = ValueInvocation.fromCompiler(f.host, f.invocation.range, f.callable).flowRefined()
        val source = declarationReceiver(f, originalCall)
        val distinctSource = declarationReceiver(f, call)
        val original = application(source, originalCall, f.artifact)
        val distinct = application(distinctSource, call, f.artifact)
        assertEquals(source, distinctSource)
        assertEquals(original.invocation, distinct.invocation)
        assertNotEquals(original.binding, distinct.binding)
        assertEquals(2048L + 2L * host.detachedTextUnits() - 8L,
            bytes(f, distinctSource, distinct) - bytes(f, source, original))
    }

    private fun application(
        source: ValueSite,
        call: ValueInvocation,
        artifact: LibraryArtifactObservation,
    ) = ValueModelApplication.CollectionSelection.selectFirst(source,
        ReviewedLibraryBinding.fromCompiler(call, ReviewedValueModel.FirstOrNull, artifact).flowRefined()).flowRefined()

    private fun declarationReceiver(f: ReviewedModelOracleCase, call: ValueInvocation) =
        ValueSite.fromCompiler(f.host, flowRange(46, 50), ValueRole.Receiver(call, ValueReceiverKind.EXTENSION)).flowRefined()

    private fun bytes(f: ReviewedModelOracleCase, source: ValueSite, application: ValueModelApplication): Long {
        val request = RelationRequest.start(f.host, RelationMeaning.References,
            RelationBudget(io.github.amichne.kast.kernel.ResourceBudget(
                io.github.amichne.kast.kernel.ResultLimit.parse(4).flowRefined(),
                io.github.amichne.kast.kernel.WorkUnitLimit.parse(100).flowRefined(),
                io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(1000).flowRefined()),
                RelationByteLimit.parse(4_000_000).flowRefined()), RelationSearchBoundary.WORKSPACE_EXPANSION)
        return ValueFlowStep.detachedByteCount(source, request, emptyList(), emptyList(), applications = listOf(application)).value
    }
}
