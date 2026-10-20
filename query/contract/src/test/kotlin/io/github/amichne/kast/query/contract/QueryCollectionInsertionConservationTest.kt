package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCollectionInsertionConservationTest {
    @Test
    fun `insertion keeps model provenance read ordinals and an independent external obligation`() {
        val f = CollectionInsertionLedgerCase()
        val ledger = f.ledger().modelRefined()
        assertTrue(ledger.representationModels.isEmpty())
        assertEquals(2, ledger.paths.size)
        assertTrue(ledger.paths.all { it.representation == QueryImpactRepresentation.NotModeled })
        assertEquals(0, (f.application.target.valuePath.segments.single() as ValuePathSegment.Element).index.value)
        assertFalse(ledger.hasForeignBasis(f.base.lease))
        assertEquals(
            setOf(QueryImpactRequiredObligation.NATIVE_FLOW),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
        assertEquals(
            6,
            QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.NATIVE_READS).modelRefined().value,
        )
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, 6).modelRefined()
        assertEquals((0..5).toList(), view.entries.map { it.ordinal.value })
        val entry = view.entries[4].evidence as QueryImpactWitnessEntry.ValueModelApplication
        assertEquals(1, entry.observationOrdinal.value)
        assertEquals(0, entry.applicationOrdinal.value)
        assertSame(f.application, entry.application)
        assertEquals(0, QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.MODELS).modelRefined().value)
    }

    @Test
    fun `insertion cannot erase its branch obligation or exact artifact binding`() {
        val f = CollectionInsertionLedgerCase()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            f.ledger(paths = listOf(f.obligationPath)),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            f.ledger(paths = listOf(f.applicationPath)),
        )
        val changed = f.application("another-module")
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_MODELED_VALUE_STEP),
            f.ledger(observation = f.observe(listOf(changed))),
        )
        val applicationOnly = f.observe(obligations = emptyList())
        assertEquals(
            Refinement.Rejected(QueryImpactPathFailure.TERMINAL_UNPROVEN),
            QueryImpactTerminal.SupportedDomainEnd.admit(applicationOnly),
        )
    }

    @Test
    fun `insertion storage visits source target full binding and the fixed index record`() {
        val f = CollectionInsertionLedgerCase()
        val graph = QueryImpactRetainedGraph()
        f.source.storageBytes(graph)
        f.application.target.storageBytes(graph)
        f.application.binding.storageBytes(graph)
        // The application and zero-index records each cost 520 bytes. Three existing field references cost 24.
        assertEquals(1064L, f.application.storageBytes(graph))
        assertEquals(8L, f.application.storageBytes(graph))
        assertEquals(8L, f.application.binding.invocation.storageBytes(graph))
        assertEquals(8L, f.application.binding.invocation.executionOwner.enclosing.storageBytes(graph))
    }
}

private class CollectionInsertionLedgerCase {
    private val existing = QueryImpactAutomaticModelFixture()
    val base = existing.base
    private val invocation = ValueInvocation.fromCompiler(base.owner, range(70, 90), library()).modelRefined()
    val source =
        ValueSite.fromCompiler(
                base.owner,
                range(72, 78),
                ValueRole.Argument(invocation, ValueArgumentPosition.parse(0).modelRefined()),
            )
            .modelRefined()
    val application = application("packet-stdlib")
    private val intoArgument =
        ValueTransfer.fromCompiler(base.producer, source, ValueTransferKind.ARGUMENT).modelRefined()
    private val obligation = ValueFlowObligation(source, ValueFlowUnsupportedCause.EXTERNAL_CALL)
    private val resultObservation = existing.observe(application.target)
    val applicationPath =
        existing.path(
            listOf(QueryImpactStep.Compiler(intoArgument), QueryImpactStep.ModeledValue(application)),
            QueryImpactTerminal.SupportedDomainEnd.admit(resultObservation).modelRefined(),
        )
    val obligationPath =
        existing.path(listOf(QueryImpactStep.Compiler(intoArgument)), QueryImpactTerminal.Unresolved.Flow(obligation))

    fun application(module: String): ValueModelApplication.CollectionInsertion {
        val artifact = existing.artifact.copy(moduleName = LibraryModelModuleName.parse(module).modelRefined())
        val binding =
            ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.SingleElementListOf, artifact)
                .modelRefined()
        return ValueModelApplication.CollectionInsertion.insertSingle(source, binding).modelRefined()
    }

    fun observe(
        applications: List<ValueModelApplication> = listOf(application),
        obligations: List<ValueFlowObligation> = listOf(obligation),
    ) = existing.observe(source, obligations = obligations, applications = applications)

    fun ledger(
        paths: List<QueryImpactPath> = listOf(applicationPath, obligationPath),
        observation: ValueFlowStep = observe(),
    ) =
        QueryImpactLedger.fromEvidence(
            seeds = listOf(base.producer),
            domain = base.domain.boundary,
            semantics = QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            representationModels = emptyList(),
            boundaryModels = emptyList(),
            observations =
                listOf(existing.observe(base.producer, listOf(intoArgument)), observation, resultObservation),
            paths = paths,
            originalProducers = listOf(base.producerWitness),
        )

    private fun library(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function("kotlin.collections.listOf", null, emptyList(), listOf("T"), 1)
                .modelRefined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse(
                                QueryImpactAutomaticModelFixture.ROOT + "kotlin/collections/CollectionsKt.class"
                            )
                            .modelRefined()
                    ),
                    20000,
                    20100,
                    "listOf",
                    "kotlin.collections.listOf",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .modelRefined()
        return RelationEndpoint.resolve(base.lease, existing.invocation.callable.scope, evidence).modelRefined()
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).modelRefined()
}
