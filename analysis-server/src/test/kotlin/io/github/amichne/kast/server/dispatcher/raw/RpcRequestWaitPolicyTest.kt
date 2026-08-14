package io.github.amichne.kast.server

import io.github.amichne.kast.server.dispatch.RpcRequestWaitPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RpcRequestWaitPolicyTest {
    @Test
    fun `all reconciliation RPCs delegate their deadline to backend progress`() {
        val config = AnalysisServerConfig(requestTimeoutMillis = 1)

        listOf(
            "raw/workspace-refresh",
            "raw/apply-edits",
            "raw/exact-file-image-cas",
            "raw/recover-mutation-scratch",
            "change/apply-add-declaration",
        ).forEach { method ->
            assertEquals(
                RpcRequestWaitPolicy.BackendProgressDeadline,
                RpcRequestWaitPolicy.derive(method, config),
                method,
            )
        }
    }

    @Test
    fun `semantic graph deadline budgets both graph passes around reconciliation`() {
        val policy = RpcRequestWaitPolicy.derive(
            "raw/semantic-graph",
            AnalysisServerConfig(requestTimeoutMillis = 1),
        )

        val deadline = assertInstanceOf(
            RpcRequestWaitPolicy.ServerDeadline.WorkspaceTransition::class.java,
            policy,
        )
        assertEquals(3_600_002L, deadline.timeoutMillis)
    }

    @Test
    fun `semantic graph deadline scales both ordinary graph passes`() {
        val policy = RpcRequestWaitPolicy.derive(
            "raw/semantic-graph",
            AnalysisServerConfig(requestTimeoutMillis = 35_000),
        )

        val deadline = assertInstanceOf(
            RpcRequestWaitPolicy.ServerDeadline.WorkspaceTransition::class.java,
            policy,
        )
        assertEquals(3_670_000L, deadline.timeoutMillis)
    }

    @Test
    fun `unrelated methods retain an ordinary server deadline`() {
        assertEquals(
            RpcRequestWaitPolicy.ServerDeadline.Ordinary.derive(17),
            RpcRequestWaitPolicy.derive(
                "raw/diagnostics",
                AnalysisServerConfig(requestTimeoutMillis = 17),
            ),
        )
    }
}
