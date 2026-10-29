package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliFailureCause
import io.github.amichne.kast.cli.boundaryExit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicRuntimeFailureTest {
    @Test
    fun `typed transport and protocol failures remain distinct from absent installation`() {
        assertEquals(
            5,
            classifyPublicRuntimeFailure(
                    DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.UNAVAILABLE)
                )
                .code,
        )
        assertEquals(
            6,
            classifyPublicRuntimeFailure(
                    DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.RESPONSE_REJECTED)
                )
                .code,
        )
        assertEquals(
            4,
            classifyPublicRuntimeFailure(
                    DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.KAST_EXECUTABLE_UNAVAILABLE)
                )
                .code,
        )
    }

    @Test
    fun `transport deadline never invents a semantic elapsed grant`() {
        val failure =
            DaemonOperationClientRejection.Server(DaemonOperationFailure.Host(ExistingIdeFailure.DEADLINE_EXCEEDED))
        assertEquals(5, classifyPublicRuntimeFailure(failure).code)
        val exit =
            boundaryExit(CliBoundaryExitStatus.RUNTIME, "deadline").copy(cause = CliFailureCause.Runtime(failure))
        assertEquals(4, exit.code)
    }
}
