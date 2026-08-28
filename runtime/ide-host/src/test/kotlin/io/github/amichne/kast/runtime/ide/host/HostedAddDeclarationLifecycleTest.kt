package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.change.verify.ChangeApplicationIdentity
import io.github.amichne.kast.change.verify.ChangeApplicationIssuance
import io.github.amichne.kast.change.verify.ChangeApplicationLookup
import io.github.amichne.kast.change.verify.ChangePlanIdentity
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangePlanLookup
import io.github.amichne.kast.change.verify.ChangeReceiptIssuance
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.change.verify.DurableChangeAuthorityFailure
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.runtime.server.RuntimeServer
import io.github.amichne.kast.runtime.server.RuntimeServerConstruction
import io.github.amichne.kast.runtime.server.ServerDispatch
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HostedAddDeclarationLifecycleTest {
    @Test
    fun `hosted mutation table exposes exactly four lifecycle routes and only add declaration`() =
        runTest {
            val planningCalls = AtomicInteger()
            val clean = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ ->
                    planningCalls.incrementAndGet()
                    HostedChangePlanningResult.Rejected(HostedMutationAdmissionFailure.INTENT_REJECTED)
                },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = ChangeRecoveryOperations { error("not invoked") },
            )
            val mutationBindings = HostedMutationProtocol.bindings(clean, missingSelectors, rejectedAuthority)
            assertEquals(
                setOf(
                    CanonicalOperation.CHANGE_PLAN,
                    CanonicalOperation.CHANGE_APPLY,
                    CanonicalOperation.CHANGE_VERIFY,
                    CanonicalOperation.CHANGE_RECOVER,
                ),
                mutationBindings.mapTo(linkedSetOf()) { it.operation },
            )
            val server = RuntimeServer.createHostedEffects(
                topologyBindings() + mutationBindings,
            ).created()
            val request = ChangePlanRequest(
                ChangeIntentDocument.AddFile(
                    ProtocolText.parse("Other.kt").refined(),
                    ProtocolText.parse("class Other").refined(),
                ),
            )
            val encoded = CanonicalOperationWireBindings.changePlan.encodeRequest(request).encoded()
            val response = server.dispatch(encoded).responded()
            assertEquals(
                OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED),
                CanonicalOperationWireBindings.changePlan.decodeOutcome(response).decoded(),
            )
            assertEquals(0, planningCalls.get())
        }

    @Test
    fun `recovery required state cannot carry planning or application authority`() {
        val state = HostedMutationState.RecoveryRequired(ChangeRecoveryOperations { error("not invoked") })
        assertFalse(state::class.java.declaredFields.any { it.type == Boolean::class.javaPrimitiveType })
        assertEquals(
            listOf("recovery"),
            state::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") },
        )
    }

    @Test
    fun `live recovery transition drops clean mutation authority without a boolean protocol`() {
        val recovery = ChangeRecoveryOperations { error("not invoked") }
        val runtime = HostedMutationRuntimeState(
            HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ -> error("not invoked") },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = recovery,
            ),
        )

        runtime.requireRecovery()

        assertEquals(HostedMutationState.RecoveryRequired(recovery), runtime.current())
        assertFalse(runtime::class.java.declaredFields.any { it.type == Boolean::class.javaPrimitiveType })
    }

    private fun topologyBindings() = HostedTopologyProtocol.bindings(
        HostedTopologyOperations(
            TopologyBuildOperations {
                TopologyBuildResult.Rejected(TopologyBuildFailure.WorkspaceNotReady)
            },
            TraversalOperations {
                TraversalResult.Rejected(TraversalRejection.RequiredEvidenceUnavailable)
            },
        ),
        missingSelectors,
    )

    private fun RuntimeServerConstruction.created(): RuntimeServer = when (this) {
        is RuntimeServerConstruction.Created -> server
        is RuntimeServerConstruction.Rejected -> error(failures.toString())
    }

    private fun WireEncoding.encoded(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error(failure.toString())
    }

    private fun ServerDispatch.responded(): String = when (this) {
        is ServerDispatch.Responded -> document
        is ServerDispatch.Rejected -> error(failure.toString())
    }

    private fun <Value> WireDecoding<Value>.decoded(): Value = when (this) {
        is WireDecoding.Decoded -> value
        is WireDecoding.Rejected -> error(failure.toString())
    }

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error(failure.toString())
        }

    private companion object {
        val missingSelectors = object : HostedExactSelectorOperations {
            override fun issueExact(selector: io.github.amichne.kast.symbol.contract.SymbolSelector) =
                HostedExactIssuance.Rejected

            override suspend fun exact(token: ProtocolText) = HostedExactLookup.Missing
        }

        val rejectedAuthority = object : DurableChangeAuthority {
            override fun issuePlan(plan: io.github.amichne.kast.change.contract.ChangePlan) =
                ChangePlanIssuance.Rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)

            override fun loadPlan(identity: ChangePlanIdentity) = ChangePlanLookup.Missing

            override fun issueApplication(
                plan: io.github.amichne.kast.change.contract.ChangePlan,
                application: io.github.amichne.kast.change.apply.AppliedUnverified,
            ) = ChangeApplicationIssuance.Rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)

            override fun loadApplication(identity: ChangeApplicationIdentity) =
                ChangeApplicationLookup.Missing

            override fun issueReceipt(receipt: io.github.amichne.kast.change.verify.VerifiedReceipt) =
                ChangeReceiptIssuance.Rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)
        }
    }
}
