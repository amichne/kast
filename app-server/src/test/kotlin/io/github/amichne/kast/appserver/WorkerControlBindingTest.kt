package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkerControlBindingTest {
    @Test
    fun `wire ready retains coordinator owner epoch generation and selected configuration`() {
        val binding =
            WorkerRouteBinding.Installation.admit(
                    "installation",
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000002",
                    "a".repeat(64),
                    7,
                )
                .refined()
        val endpoint =
            InstalledWorkerEndpoint.admit(
                    Path.of("/workspace"),
                    SemanticRuntimeId.parse("sha256:" + "b".repeat(64)).refined(),
                    Path.of("/runtime.sock"),
                    SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001").refined(),
                )
                .refined()
        val decoded =
            WorkerControlReply.decode(WorkerControlReply.encode(InstalledWorkerStart.Ready(endpoint, binding)))
                as InstalledWorkerStart.Ready
        val retained =
            assertInstanceOf(
                WorkerRouteBinding.Installation::class.java,
                decoded.binding,
                "control wire erased admitted owner",
            )
        assertEquals(binding.installationId, retained.installationId)
        assertEquals(binding.stateEpoch, retained.stateEpoch)
        assertEquals(binding.serviceGeneration, retained.serviceGeneration)
        assertEquals(binding.configurationIdentity, retained.configurationIdentity)
        assertEquals(7L, retained.workspaceRevision)
        val stopped = WorkerStopReply.encode(InstalledWorkerStop.Stopped(Path.of("/workspace"), binding))
        assertInstanceOf(InstalledWorkerStop.Stopped::class.java, WorkerStopReply.decode(stopped))
        assertInstanceOf(
            InstalledWorkerStart.Rejected::class.java,
            WorkerControlReply.decode(stopped),
            "retirement must not become a ready worker route",
        )
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Rejected $failure")
    }
