package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.coroutines.runBlocking

/** Wider callback evidence must preserve a compiler-grounded escape rejection. */
class NativeCrossModuleCallbackRejectionTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testEscapedCallbackCannotCompleteWorkspaceCallers() = runBlocking {
        escaped(QueryExpansionScopeDocument.Workspace)
    }

    fun testEscapedCallbackCannotCompleteScopedCallers() = runBlocking {
        escaped(CrossModuleCallbackRequests.domain(CrossModuleCallbackUnit.CLIENT))
    }

    fun testEscapedOutsideSupplyCannotBecomeACompleteEmptyResult() = runBlocking {
        escaped(CrossModuleCallbackRequests.domain(CrossModuleCallbackUnit.RELAY))
    }

    private suspend fun escaped(destination: QueryExpansionScopeDocument) {
        val fixture =
            NativeCrossModuleCallbackFixture(project, getOrCreateProjectBaseDir(), module)
                .prepare(CrossModuleCallbackSupply.ESCAPED, ::registerTestProjectJdk)
        val protocol = fixture.protocol()
        val discovered =
            protocol.execute(
                request =
                    CrossModuleCallbackRequests.discover(
                        CrossModuleCallbackUnit.SIGNAL,
                        "pulse",
                        CrossModuleCallbackSupply.ESCAPED,
                    ),
                lease = fixture.lease,
                budget = CrossModuleCallbackRequests.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        assertTrue("The rejection control must resolve its root", discovered is OperationOutcome.Complete)
        val selected =
            (discovered as OperationOutcome.Complete).evidence.payload.items.values.single()
                as QueryResultItemDocument.ExactSymbol
        val outcome =
            protocol.execute(
                request =
                    CrossModuleCallbackRequests.related(
                        selected.ref,
                        RelationKindDocument.CALLERS,
                        destination,
                    ),
                lease = fixture.lease,
                budget = CrossModuleCallbackRequests.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        assertTrue("Escaped supply must reject completion", outcome is OperationOutcome.Rejected)
        val reason = (outcome as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven
        assertEquals(QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN, reason.reason)
        val cause = checkNotNull(reason.callbackGraphFailure).cause as QueryCallbackGraphCauseDocument.Unresolved
        assertTrue(QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES in cause.obligations.values)
    }
}
