package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** The fixed original prefix admits the exact FunctionArgument before a smaller callback grant starts. */
internal class NativePacketCallbackGrantFixture(
    private val project: Project,
    private val fixture: NativePacketValueFixture,
    private val expected: NativePacketCompositeSuccessSource,
) {
    fun originalArgument(): ValueSite {
        val request =
            fixture.selection(
                PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(WIRE, "Call", "execute"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue("Original S2 seed prerequisite failed: $result", result is ValueProducerSeedRead.Seeded)
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.invocation.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        val receiver = next(seed.site, ValueTransferKind.RECEIVER)
        val formal = next(receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val fields = observed(formal)
        val anchor =
            fixture.argumentExpression(PacketDeclaration(OUTCOME, "Outcome", "map"), "transform", "value").anchor
        val candidates =
            fields.transfers.filter { it.kind == ValueTransferKind.FIELD_READ && it.target.range == anchor }
        assertEquals(
            "Original Success.value field prerequisite: ${fields.obligations.map { it.cause }}",
            1,
            candidates.size,
        )
        val transfer = candidates.single()
        val evidence = (transfer.evidence as ValueTransferEvidence.Guarded).delegate as ValueSourceTransfer
        val field = (evidence.operation as ValueSourceOperation.ReadField).field
        assertEquals(expected.field(OUTCOME, "Success", "value"), field)
        return next(transfer.target, ValueTransferKind.ARGUMENT).also { site ->
            assertTrue(site.role is ValueRole.FunctionArgument)
        }
    }

    fun budget(grant: Long): RelationBudget =
        fixture.budget.copy(
            resources = fixture.budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(grant).nativeRefined())
        )

    fun read(
        source: ValueSite,
        budget: RelationBudget = fixture.budget,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): ValueFlowRead = runBlocking {
        IntellijValueFlowCompilerAdapter(observation)
            .read(
                project = project,
                current = fixture.lease,
                request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                model = fixture.model,
            )
    }

    private fun next(source: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = observed(source)
        assertEquals(
            "Original S2 prerequisite frontier=${step.obligations.map { it.cause }}",
            listOf(kind),
            step.transfers.map { it.kind },
        )
        return step.transfers.single().target
    }

    private fun observed(source: ValueSite): ValueFlowStep {
        val result = read(source)
        assertTrue("Original S2 prerequisite read failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private companion object {
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val WIRE = NativePacketCompositeSuccessSource.WIRE
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
    }
}
