@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.query.WorkspaceLifecycleToolInput
import io.github.amichne.kast.protocol.registry.OperationEffect
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceInspectionIngressRecoveryTest {
    @Test
    fun `unqualified unknown and effectful lifecycle inputs retain conservative execution access`() {
        val fixture = WorkspaceInspectionIngressFixture()
        val effect = BrokerOperationEffect.Canonical(OperationEffect.WORKSPACE_MODEL_WRITE)
        val operation = WorkspaceExecutionAccess.Operation(effect)
        val open = fixture.params(WorkspaceLifecycleToolInput.Open("/workspace", "open-request"))
        assertEquals(operation, workspaceInvocationAccess(open, fixture.broker, fixture.definitions))
        assertEquals(operation, workspaceInvocationAccess(fixture.params, fixture.broker, emptyList()))
        assertEquals(
            operation,
            workspaceInvocationAccess(
                fixture.params,
                fixture.broker,
                fixture.definitions.map { it.copy(effect = OperationEffect.INTELLIJ_READ) },
            ),
        )
        val unknown = WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Unknown)
        for (params in
            listOf(
                fixture.params(WorkspaceLifecycleToolInput.Inspect(), namespace = "unknown"),
                fixture.params(WorkspaceLifecycleToolInput.Inspect(), tool = "unknown"),
            )) assertEquals(unknown, workspaceInvocationAccess(params, fixture.broker, fixture.definitions))
    }

    @Test
    fun `qualified inspection traverses real ingress lane and projection while provider runs and retires`() = runTest {
        val case =
            WorkspaceInspectionIngressRecoveryCase(this, BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ))
        try {
            runCurrent()
            case.inspect(
                "running",
                WorkspaceExecutionLaneState.BUSY,
                WorkspaceExecutionNextAction.OBSERVE_PROVIDER_SETTLEMENT,
            )
            case.cancel()
            case.inspect(
                "retiring",
                WorkspaceExecutionLaneState.BUSY,
                WorkspaceExecutionNextAction.OBSERVE_PROVIDER_SETTLEMENT,
            )
            case.settle(mutationFence = false)
            case.inspect(
                "settled",
                WorkspaceExecutionLaneState.IDLE,
                WorkspaceExecutionNextAction.REOBSERVE_NATIVE_AUTHORITY,
            )
            case.assertDelivered()
        } finally {
            case.close()
        }
    }

    @Test
    fun `qualified inspection crosses uncertain write and unknown fences while execution stays rejected`() = runTest {
        for (effect in
            listOf(
                BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_WRITE),
                BrokerOperationEffect.Unknown,
            )) {
            val case = WorkspaceInspectionIngressRecoveryCase(this, effect)
            try {
                runCurrent()
                case.inspect(
                    "running",
                    WorkspaceExecutionLaneState.BUSY,
                    WorkspaceExecutionNextAction.OBSERVE_PROVIDER_SETTLEMENT,
                )
                case.cancel()
                case.inspect(
                    "retiring",
                    WorkspaceExecutionLaneState.BUSY,
                    WorkspaceExecutionNextAction.OBSERVE_PROVIDER_SETTLEMENT,
                )
                case.settle(mutationFence = true)
                case.inspect(
                    "fenced",
                    WorkspaceExecutionLaneState.RECOVERY_REQUIRED,
                    WorkspaceExecutionNextAction.RECONCILE_UNCERTAIN_MUTATION,
                )
                case.assertDelivered()
            } finally {
                case.close()
            }
        }
    }
}
