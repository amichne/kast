package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import kotlinx.coroutines.runBlocking

/** An empty production inventory does not establish that the declared call is external. */
class NativePacketSourcePolicyTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-source-policy-jdk"

    fun testProductionInventoryKeepsUnknownDispatchWhenOnlyTestImplementationExists() {
        val fixture = packetFixture(SOURCES, SymbolSourceKindPolicy.PRODUCTION_ONLY)
        val argument = argument(fixture)
        val step = step(fixture, argument)
        assertTrue(step.transfers.isEmpty())
        val obligation = step.obligations.single()
        val dispatch = (obligation.evidence as ValueFlowObligationEvidence.ImplementationDispatch).dispatch
        assertEquals(SymbolSourceKindPolicy.PRODUCTION_ONLY, dispatch.batch.request.searchScope.sourceKinds)
        assertEquals(
            (argument.role as ValueRole.Argument).call.callable.valueIdentity,
            dispatch.batch.request.subject.valueIdentity,
        )
        assertTrue(dispatch.batch.facts.isEmpty())
        assertTrue(dispatch.batch.scopeExclusions.isEmpty())
        assertTrue(dispatch.coverage is ValueImplementationCoverage.Exhausted)
        assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, obligation.cause)
    }

    fun testCombinedSourcePolicyKeepsTheExactTestImplementationAndMatchedReturn() {
        val fixture = packetFixture(SOURCES, SymbolSourceKindPolicy.PRODUCTION_AND_TEST)
        val argument = argument(fixture)
        val entered = step(fixture, argument)
        assertTrue(entered.obligations.isEmpty())
        val transfer = entered.transfers.single()
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, transfer.kind)
        val frame = transfer.target.callStack.frames.single()
        val target = frame.target as ValueCallTarget.Implementation
        assertEquals(WorkspaceSourceRootKind.TEST, target.ownership.root.sourceKind)
        assertEquals(target.relation.source.valueIdentity, target.ownership.declaration)
        var site = transfer.target
        for (kind in
            listOf(ValueTransferKind.LOCAL_READ, ValueTransferKind.RETURN, ValueTransferKind.SOURCE_CALL_RETURN)) {
            site = next(fixture, site, kind)
        }
        assertEquals((argument.role as ValueRole.Argument).call.resultSite().identity, site.identity)
        assertTrue(site.callStack.frames.isEmpty())
    }

    private fun argument(fixture: NativePacketValueFixture): ValueSite {
        val request =
            fixture.selection(
                PacketDeclaration(MAIN, "PolicyCaller", "invoke"),
                "PolicyRequest",
                PacketDeclaration(MAIN, "PolicyRequest", "<init>"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Policy fixture seed failed: $result", result is ValueProducerSeedRead.Seeded)
        var site = (result as ValueProducerSeedRead.Seeded).seed.site
        for (kind in
            listOf(ValueTransferKind.LOCAL_BINDING, ValueTransferKind.LOCAL_READ, ValueTransferKind.ARGUMENT)) {
            site = next(fixture, site, kind)
        }
        return site
    }

    private fun next(fixture: NativePacketValueFixture, site: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = step(fixture, site)
        assertEquals(listOf(kind), step.transfers.map { it.kind })
        assertTrue(step.obligations.isEmpty())
        return step.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request =
                        ValueFlowRequest(
                            site,
                            RelationBudget(
                                fixture.budget.resources,
                                RelationByteLimit.parse(16_000_000L).nativeRefined(),
                            ),
                            RelationSearchBoundary.RETAINED_SUBJECT,
                        ),
                    model = fixture.model,
                )
        }
        assertTrue("Policy fixture read failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private companion object {
        const val MAIN = "src/main/kotlin/fixture/controls/PolicyMapper.kt"
        val SOURCES = listOf(MAIN, "src/test/kotlin/fixture/controls/TestPolicyMapper.kt")
    }
}
