package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.coroutines.runBlocking

/** Real module dependencies and canonical queries expose traversal scope loss in callback proof. */
class NativeCrossModuleCallbackTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testDirectCallerControl() = runBlocking {
        val result =
            related(
                supply = CrossModuleCallbackSupply.DIRECT,
                root = CrossModuleCallbackUnit.SIGNAL,
                name = "pulse",
                relation = RelationKindDocument.CALLERS,
                destination = CrossModuleCallbackUnit.CLIENT,
            )
        val callers =
            result.items.values.map { identity(checkNotNull((it as QueryResultItemDocument.ExactSymbol).signature)) }
        assertEquals(listOf("client.direct"), callers)
    }

    fun testScopedCallersKeepTheOutsideReceiverAndForwarding() = runBlocking {
        val result =
            related(
                supply = CrossModuleCallbackSupply.ANONYMOUS,
                root = CrossModuleCallbackUnit.SIGNAL,
                name = "pulse",
                relation = RelationKindDocument.CALLERS,
                destination = CrossModuleCallbackUnit.CLIENT,
            )
        assertTrue("A non-inline callback must not fabricate a named caller", result.items.values.isEmpty())
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        assertEquals("client.supply", identity(callback.lexicalOwner.compilerTarget.compilerEvidence.signature))
        assertEquals("signal.pulse", identity(callback.target.compilerTarget.compilerEvidence.signature))
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.Bound
        assertEquals("relay.forward", identity(binding.callable.compilerTarget.compilerEvidence.signature))
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, flow.scan)
        assertTrue(flow.obligations.values.isEmpty())
        assertEquals(1, flow.invocations.values.size)
    }

    private suspend fun related(
        supply: CrossModuleCallbackSupply,
        root: CrossModuleCallbackUnit,
        name: String,
        relation: RelationKindDocument,
        destination: CrossModuleCallbackUnit,
    ): QueryRunResult {
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
        assertTrue(
            "The real module fixture must resolve its exact root: $discovered",
            discovered is OperationOutcome.Complete,
        )
        val selected =
            (discovered as OperationOutcome.Complete).evidence.payload.items.values.single()
                as QueryResultItemDocument.ExactSymbol
        val outcome =
            protocol.execute(
                request = CrossModuleCallbackRequests.related(selected.ref, relation, destination),
                lease = fixture.lease,
                budget = CrossModuleCallbackRequests.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        println("Cross-module $relation terminations=${fixture.observation.terminations}")
        println("Cross-module discovery and relation counters=${fixture.observation.snapshot()}")
        assertTrue(
            "Expected complete $relation with outside callback evidence: $outcome",
            outcome is OperationOutcome.Complete,
        )
        return (outcome as OperationOutcome.Complete).evidence.payload
    }

    private fun identity(signature: CompilerSignatureDocument): String =
        when (signature) {
            is CompilerSignatureDocument.Function -> signature.qualifiedIdentity.value
            is CompilerSignatureDocument.Property,
            is CompilerSignatureDocument.LocalFunction,
            is CompilerSignatureDocument.LocalProperty,
            is CompilerSignatureDocument.AnonymousObject,
            is CompilerSignatureDocument.TypeAlias,
            is CompilerSignatureDocument.ClassLike -> error("The fixture expects named function identities")
        }
}
