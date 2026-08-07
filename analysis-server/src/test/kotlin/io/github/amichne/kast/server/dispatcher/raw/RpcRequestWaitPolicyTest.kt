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
        ).forEach { method ->
            assertEquals(
                RpcRequestWaitPolicy.BackendProgressDeadline,
                RpcRequestWaitPolicy.derive(method, config),
                method,
            )
        }
    }

    @Test
    fun `semantic graph receives a finite transition aware server deadline`() {
        val policy = RpcRequestWaitPolicy.derive(
            "raw/semantic-graph",
            AnalysisServerConfig(requestTimeoutMillis = 1),
        )

        val deadline = assertInstanceOf(
            RpcRequestWaitPolicy.ServerDeadline.WorkspaceTransition::class.java,
            policy,
        )
        assertEquals(3_605_000L, deadline.timeoutMillis)
    }
}
