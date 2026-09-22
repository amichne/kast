package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonManagementExchangeTest {
    private val target =
        DaemonManagementTarget(
            "sha256:${"a".repeat(64)}",
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            "b".repeat(64),
        )

    @Test
    fun `lost replies and failed sends remain unobserved and are not retried`(@TempDir directory: Path) = runBlocking {
        val root = checkNotNull(CanonicalBrokerDirectory.admit(directory.toRealPath()))
        val cases =
            listOf(
                BrokerUpstreamSend.REJECTED to BrokerUpstreamFrame.Closed,
                BrokerUpstreamSend.SENT to BrokerUpstreamFrame.Closed,
                BrokerUpstreamSend.SENT to BrokerUpstreamFrame.Rejected,
            )
        cases.forEach { (sent, reply) ->
            var sends = 0
            var receives = 0
            var closes = 0
            val connection =
                object : BrokerUpstreamConnection {
                    override suspend fun send(message: String): BrokerUpstreamSend {
                        sends++
                        assertEquals(
                            DaemonManagementRequest.RegisterWorkspace(target, root.path.toString()),
                            DaemonManagementProtocol.json.decodeFromString<DaemonManagementRequest>(message),
                        )
                        return sent
                    }

                    override suspend fun receive(): BrokerUpstreamFrame {
                        receives++
                        return reply
                    }

                    override suspend fun close() {
                        closes++
                    }
                }
            assertEquals(
                Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.OUTCOME_UNOBSERVED)),
                exchangeDaemonRegistration(connection, target, root),
            )
            assertEquals(1, sends)
            assertEquals(if (sent == BrokerUpstreamSend.SENT) 1 else 0, receives)
            assertEquals(0, closes, "the connection owner retains closing responsibility")
        }
    }

    @Test
    fun `admitted registration cannot follow a later path alias`(@TempDir directory: Path) {
        val path = Files.createDirectory(directory.resolve("workspace")).toRealPath()
        val admitted = checkNotNull(CanonicalBrokerDirectory.admit(path))
        val elsewhere = Files.createDirectory(directory.resolve("elsewhere")).toRealPath()
        Files.delete(path)
        Files.createSymbolicLink(path, elsewhere)
        val registry = directory.toRealPath().resolve("registry/workspaces.json")
        assertEquals(
            Refinement.Rejected(EnrollmentFailure.PATH_REJECTED),
            WorkspaceEnrollmentStore(registry).enroll(admitted),
        )
        assertFalse(Files.exists(registry))
    }
}
