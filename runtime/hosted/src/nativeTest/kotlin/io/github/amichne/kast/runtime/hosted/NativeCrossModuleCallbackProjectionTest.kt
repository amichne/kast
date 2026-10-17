package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultProjectionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultSiteDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.coroutines.runBlocking

/** Native result eligibility needs an actual selected site after the complete outside-source proof. */
class NativeCrossModuleCallbackProjectionTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testNamedCallersKeepTheInsideInvocationAndOutsideReference() = runBlocking {
        val observed =
            related(
                supply = CrossModuleCallbackSupply.NAMED,
                root = CrossModuleCallbackUnit.SIGNAL,
                name = "pulse",
                relation = RelationKindDocument.CALLERS,
                destination = CrossModuleCallbackUnit.RELAY,
            )
        assertTrue(
            "A callable reference must not fabricate a named caller edge",
            observed.result.items.values.isEmpty(),
        )
        val reference =
            observed.result.relationObservations.values
                .flatMap { it.callableObservations.values }
                .map { it.target }
                .filterIsInstance<QueryCallableTargetDocument.NamedReference>()
                .single()
        assertEquals("signal.pulse", identity(reference.reference.target.compilerTarget.compilerEvidence.signature))
        val flow = reference.reference.flow as QueryNamedCallbackReferenceFlowDocument.Supplied
        val invocation = flow.invocations.values.single()
        val owner = invocation.owner as QueryCallbackBodyDocument.Named
        assertEquals("relay.deliver", identity(owner.callable.compilerTarget.compilerEvidence.signature))
        val selected = reference.resultProjection as QueryCallbackResultProjectionDocument.Admitted
        assertEquals(RelationKindDocument.CALLERS, selected.relation)
        assertEquals(invocation, (selected.site as QueryCallbackResultSiteDocument.Invocation).invocation)
    }

    fun testCompleteOutsideCallbackCannotEnterTheSelectedCallers() = runBlocking {
        val observed =
            related(
                supply = CrossModuleCallbackSupply.ANONYMOUS,
                root = CrossModuleCallbackUnit.SIGNAL,
                name = "pulse",
                relation = RelationKindDocument.CALLERS,
                destination = CrossModuleCallbackUnit.SIGNAL,
            )
        assertTrue(observed.result.items.values.isEmpty())
        assertTrue(observed.result.relationObservations.values.all { it.callbackObservations.values.isEmpty() })
        assertEquals(2L, observed.counters[IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED])
        assertTrue(observed.counters.getOrDefault(IntellijReadCounter.CALLBACK_RESULTS_EXCLUDED, 0L) > 0L)
    }

    fun testCompleteOutsideSuppliersCannotEnterTheSelectedCallees() = runBlocking {
        val observed =
            related(
                supply = CrossModuleCallbackSupply.NAMED,
                root = CrossModuleCallbackUnit.RELAY,
                name = "deliver",
                relation = RelationKindDocument.CALLEES,
                destination = CrossModuleCallbackUnit.RELAY,
            )
        assertTrue(observed.result.items.values.isEmpty())
        assertTrue(
            observed.result.relationObservations.values
                .flatMap { it.callableObservations.values }
                .none { it.target is QueryCallableTargetDocument.ParameterInvocation }
        )
        assertEquals(2L, observed.counters[IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_COMPLETED])
        assertEquals(1L, observed.counters[IntellijReadCounter.CALLBACK_SUPPLIER_VALUES_CONFIRMED])
        assertTrue(observed.counters.getOrDefault(IntellijReadCounter.CALLBACK_RESULTS_EXCLUDED, 0L) > 0L)
    }

    private suspend fun related(
        supply: CrossModuleCallbackSupply,
        root: CrossModuleCallbackUnit,
        name: String,
        relation: RelationKindDocument,
        destination: CrossModuleCallbackUnit,
    ): ObservedResult {
        val fixture =
            NativeCrossModuleCallbackFixture(project, getOrCreateProjectBaseDir(), module)
                .prepare(supply, ::registerTestProjectJdk)
        val protocol = fixture.protocol()
        val discovered =
            protocol.execute(
                request = CrossModuleCallbackRequests.discover(root, name, supply),
                lease = fixture.lease,
                budget = CrossModuleCallbackRequests.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        assertTrue("The fixture declaration must resolve before the relation", discovered is OperationOutcome.Complete)
        val selected =
            (discovered as OperationOutcome.Complete).evidence.payload.items.values.single()
                as QueryResultItemDocument.ExactSymbol
        val outcome =
            protocol.execute(
                request =
                    CrossModuleCallbackRequests.related(
                        selected.ref,
                        relation,
                        CrossModuleCallbackRequests.domain(destination),
                    ),
                lease = fixture.lease,
                budget = CrossModuleCallbackRequests.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        assertTrue("The fully proved outside path must complete: $outcome", outcome is OperationOutcome.Complete)
        return ObservedResult((outcome as OperationOutcome.Complete).evidence.payload, fixture.observation.snapshot())
    }

    private fun identity(signature: CompilerSignatureDocument): String {
        assertTrue(
            "The selected compiler declaration must be a named function",
            signature is CompilerSignatureDocument.Function,
        )
        return (signature as CompilerSignatureDocument.Function).qualifiedIdentity.value
    }

    private data class ObservedResult(val result: QueryRunResult, val counters: Map<IntellijReadCounter, Long>)
}
