package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.runBlocking

class NativePacketKnownDeclarationsForwardingTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-known-declarations-forwarding-jdk"

    fun testOrdinaryCompilerStepAndReceiptKeepTheExactKnownCarrier() {
        val case = preparedCase()
        val known = known(case)
        val step = observed(read(case = case, site = case.seed.site, known = known))
        assertEquals(listOf(ValueTransferKind.LOCAL_BINDING), step.transfers.map { it.kind })
        assertCarrier(known, step)
    }

    fun testLocalReferenceScanAndReceiptKeepTheExactKnownCarrier() {
        val case = preparedCase()
        val first = observed(read(case, case.seed.site))
        assertEquals(listOf(ValueTransferKind.LOCAL_BINDING), first.transfers.map { it.kind })
        val binding = first.transfers.single().target
        val known = known(case)
        val step = observed(read(case = case, site = binding, known = known))
        assertEquals(listOf(ValueTransferKind.LOCAL_READ), step.transfers.map { it.kind })
        assertCarrier(known, step)
    }

    fun testForeignKnownAuthorityRejectsBeforeNativeEffectsWithZeroWork() {
        val case = preparedCase()
        val target = case.current.endpoint
        val evidence = freshEvidence(case)
        RevalidatedRelationEndpoint.validate(target, evidence).nativeRefined()
        val foreignLease =
            SemanticReadLease(
                case.fixture.lease.workspaceRoot,
                EvidenceGeneration.parse(FOREIGN_GENERATION).nativeRefined(),
            )
        val foreign =
            RelationEndpoint.resolve(
                    lease = foreignLease,
                    scope = target.scope,
                    evidence = evidence,
                    constraints = target.constraints,
                )
                .nativeRefined()
        val proof = RevalidatedRelationEndpoint.validate(foreign, evidence).nativeRefined()
        val known = ValueKnownDeclarations.admit(listOf(proof), foreignLease).nativeRefined()
        val effects = KnownForwardingEffects()
        val read = read(case = case, site = case.seed.site, known = known, observation = effects)
        assertTrue(
            "Foreign known authority needs BASIS_MISMATCH: ${status(read)}",
            read is ValueFlowRead.ContractRejected,
        )
        read as ValueFlowRead.ContractRejected
        assertEquals(ValueFlowStepFailure.BASIS_MISMATCH, read.cause)
        assertEquals(0L, read.examinedWorkUnits.value)
        assertEquals(emptyMap<IntellijReadCounter, Int>(), effects.counts)
        assertEquals(emptyList<IntellijReadCall>(), effects.calls)
        assertEquals(emptyList<IntellijReadTermination>(), effects.terminations)
    }

    fun testEmptyCarrierPreservesTheOriginalCompilerRead() {
        val case = preparedCase()
        val step = observed(read(case, case.seed.site))
        assertEquals(listOf(ValueTransferKind.LOCAL_BINDING), step.transfers.map { it.kind })
        assertCarrier(ValueKnownDeclarations.Empty, step)
    }

    private fun preparedCase(): KnownForwardingCase {
        val fixture = packetFixture(listOf(V0))
        val selection =
            fixture.selection(
                owner = PacketDeclaration(V0, "Feature", "handle"),
                callee = "ClientRequest",
                callable = PacketDeclaration(V0, "ClientRequest", "<init>"),
            )
        val adapter = IntellijValueFlowCompilerAdapter()
        val seeded = runBlocking {
            adapter.seed(project = project, current = fixture.lease, request = selection, model = fixture.model)
        }
        assertTrue("Original V0 constructor prerequisite failed", seeded is ValueProducerSeedRead.Seeded)
        val current = runBlocking {
            adapter.revalidate(
                project = project,
                current = fixture.lease,
                selector = selection.expectedCallable,
                budget = fixture.budget,
                model = fixture.model,
            )
        }
        assertTrue(
            "Actual known declaration must revalidate through K2",
            current is ValueModelDeclarationRead.Revalidated,
        )
        return KnownForwardingCase(
            fixture = fixture,
            selection = selection,
            seed = (seeded as ValueProducerSeedRead.Seeded).seed,
            current = (current as ValueModelDeclarationRead.Revalidated).declaration,
        )
    }

    private fun known(case: KnownForwardingCase): ValueKnownDeclarations =
        ValueKnownDeclarations.admit(listOf(case.current, case.current), case.fixture.lease).nativeRefined()

    private fun freshEvidence(case: KnownForwardingCase): CompilerGroundedSymbolEvidence =
        ReadAction.compute<CompilerGroundedSymbolEvidence, RuntimeException> {
            val request =
                RelationRequest.start(
                    subject = case.current.endpoint,
                    meaning = RelationMeaning.References,
                    budget = case.fixture.budget,
                    boundary = case.selection.boundary,
                )
            val prepared = IntellijValueFlowCompilerAdapter().prepare(project, request, case.fixture.model)
            assertTrue(
                "Known declaration must restore through its exact native owner",
                prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready,
            )
            prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
            val projected = prepared.projection.project(prepared.owner as PsiNamedElement)
            assertTrue(
                "Known declaration needs current K2 evidence",
                projected is IntellijRelationDeclarationProjection.Projected,
            )
            (projected as IntellijRelationDeclarationProjection.Projected).evidence
        }

    private fun read(
        case: KnownForwardingCase,
        site: ValueSite,
        known: ValueKnownDeclarations = ValueKnownDeclarations.Empty,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): ValueFlowRead = runBlocking {
        IntellijValueFlowCompilerAdapter(observation)
            .read(
                project = project,
                current = case.fixture.lease,
                request =
                    ValueFlowRequest(
                        source = site,
                        budget = case.fixture.budget,
                        boundary = case.selection.boundary,
                        knownDeclarations = known,
                    ),
                model = case.fixture.model,
            )
    }

    private fun observed(read: ValueFlowRead): ValueFlowStep {
        assertTrue("Native forwarding needs an observed step: ${status(read)}", read is ValueFlowRead.Observed)
        return (read as ValueFlowRead.Observed).step
    }

    private fun assertCarrier(expected: ValueKnownDeclarations, step: ValueFlowStep) {
        assertSame(expected, step.knownDeclarations)
        assertEquals(1, step.receipts.size)
        assertSame(expected, step.receipts.single().knownDeclarations)
    }

    private fun status(read: ValueFlowRead): String =
        when (read) {
            is ValueFlowRead.Observed -> "OBSERVED"
            is ValueFlowRead.Suspended -> "SUSPENDED:${read.cause}"
            is ValueFlowRead.Rejected -> "REJECTED:${read.cause}"
            is ValueFlowRead.ContractRejected -> "CONTRACT_REJECTED:${read.cause}"
        }

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
        const val FOREIGN_GENERATION = 2L
    }
}

private data class KnownForwardingCase(
    val fixture: NativePacketValueFixture,
    val selection: ValueProducerSeedRequest,
    val seed: ValueProducerSeed,
    val current: RevalidatedRelationEndpoint,
)

private class KnownForwardingEffects : IntellijReadObservation {
    val counts = mutableMapOf<IntellijReadCounter, Int>()
    val calls = mutableListOf<IntellijReadCall>()
    val terminations = mutableListOf<IntellijReadTermination>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counts[counter] = (counts[counter] ?: 0) + amount
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls += call
        return IntellijReadCallScope.None
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        terminations += reason
    }
}
