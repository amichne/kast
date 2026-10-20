package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** The exact original property seed reaches the log argument without an invented caller frame. */
internal class NativePacketS3SeedCallbackSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun argument(): ValueSite {
        val facts = NativePacketS3ValuePrefixSource(project, base, fixture).facts()
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = facts.request,
                    model = fixture.model,
                )
        }
        assertTrue("Original adapt761:768 admission prerequisite", result is ValueProducerSeedRead.Seeded)
        val source = (result as ValueProducerSeedRead.Seeded).seed.site
        assertEquals(facts.request.anchor, source.range)
        assertEquals(ValueCallStack.Empty, source.callStack)
        val local = read(source).transfers.single { it.kind == ValueTransferKind.LOCAL_BINDING }.target
        assertEquals(facts.binding, local.range)
        sameState(source, local)
        val log =
            read(local)
                .transfers
                .single { it.kind == ValueTransferKind.LOCAL_READ && it.target.range == facts.logRead }
                .target
        sameState(local, log)
        val argument = read(log).transfers.single { it.kind == ValueTransferKind.ARGUMENT }.target
        sameState(log, argument)
        val role = argument.role as ValueRole.FunctionArgument
        assertEquals(facts.logInvocation, role.call.range)
        assertEquals("kotlin.Function1.invoke", role.call.signature.qualifiedIdentity.value)
        assertEquals(0, role.position.value)
        assertEquals(ValueCallStack.Empty, argument.callStack)
        return argument
    }

    fun read(
        source: ValueSite,
        boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
    ): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, boundary),
                    model = fixture.model,
                )
        }
        assertTrue(
            "The real native read must reach its one-hop assertions: ${status(result)}",
            result is ValueFlowRead.Observed,
        )
        return (result as ValueFlowRead.Observed).step
    }

    private fun sameState(source: ValueSite, target: ValueSite) {
        assertEquals(source.enclosing, target.enclosing)
        assertEquals(source.executionOwner, target.executionOwner)
        assertEquals(source.callStack, target.callStack)
        assertEquals(source.valuePath, target.valuePath)
        assertEquals(source.predicates, target.predicates)
    }

    private fun status(result: ValueFlowRead): String =
        when (result) {
            is ValueFlowRead.Observed -> "OBSERVED"
            is ValueFlowRead.Suspended -> "SUSPENDED"
            is ValueFlowRead.Rejected -> "REJECTED:${result.cause}"
            is ValueFlowRead.ContractRejected -> "CONTRACT_REJECTED:${result.cause}"
        }
}
