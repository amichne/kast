package io.github.amichne.kast.server

import io.github.amichne.kast.server.dispatch.RpcRequestWaitPolicy
import org.junit.jupiter.api.Assertions.assertEquals
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
}
