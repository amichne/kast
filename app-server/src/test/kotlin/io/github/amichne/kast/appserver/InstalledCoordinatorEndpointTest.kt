package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledCoordinatorEndpointTest {
    @Test
    fun `explicit private coordinator serves status alongside a live native Codex endpoint`() =
        withPayload { root, kast ->
            runBlocking {
                val native = root.resolve(".codex/app-server-control/app-server-control.sock")
                Files.createDirectories(native.parent)
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { incumbent ->
                    incumbent.bind(java.net.UnixDomainSocketAddress.of(native))
                    val inode = Files.getAttribute(native, "unix:ino")
                    val options = privateOptions(kast, root)
                    val start = InstalledCoordinator.start(options)
                    assertTrue(start is InstalledCoordinatorStart.Started, start.toString())
                    val running = (start as InstalledCoordinatorStart.Started).coordinator
                    try {
                        assertNotEquals(native, options.socket.path)
                        assertEquals(
                            BrokerSocketReachability.REACHABLE,
                            JdkBrokerSocketProbe.probe(options.socket.path),
                        )
                        assertTrue(incumbent.isOpen)
                        assertEquals(inode, Files.getAttribute(native, "unix:ino"))
                    } finally {
                        running.close()
                    }
                    assertEquals(inode, Files.getAttribute(native, "unix:ino"))
                }
            }
        }

    @Test
    fun `canonical service cannot publish readiness without native Codex protocol`() = withPayload { root, kast ->
        runBlocking {
            val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
            val sink = BrokerStartupActivitySink {
                activities += it
                BrokerStartupActivityPublication.PUBLISHED
            }
            val options = managedOptions(kast, root, sink)
            val discovery =
                DesktopDaemonDiscovery(
                    object : DesktopDaemonEnvironment {
                        override fun read(): DesktopDaemonEnvironmentRead =
                            throw AssertionError("Unexpected discovery observation before native readiness")

                        override fun enable(): DesktopDiscoveryOutcome =
                            throw AssertionError("Unexpected discovery publication before native readiness")

                        override fun remove(): DesktopDiscoveryOutcome =
                            throw AssertionError("Unexpected discovery cleanup")
                    }
                )
            val started = InstalledCoordinator.start(options, discovery)
            try {
                assertEquals(InstalledCoordinatorStart.Rejected(BrokerServerFailure.NATIVE_PROTOCOL_REJECTED), started)
                assertFalse(activities.any { it.stage == BrokerStartupStage.DESKTOP_DISCOVERY })
                assertFalse(
                    activities.contains(BrokerStartupActivity.Completed(BrokerStartupStage.READINESS_PUBLICATION))
                )
                assertFalse(Files.exists(options.socket.path))
            } finally {
                if (started is InstalledCoordinatorStart.Started) started.coordinator.close()
            }
        }
    }

    private fun managedOptions(kast: Path, root: Path, sink: BrokerStartupActivitySink): InstalledCoordinatorOptions {
        val readiness =
            BrokerInstallationLayout.from(kast, root.resolve(".codex")).broker.resolve("service-readiness.json")
        val environment =
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "BROKER_SERVICE_IDENTITY" to "sha256:${"b".repeat(64)}",
                "BROKER_READINESS_FILE" to readiness.toString(),
            )
        return (InstalledCoordinatorConfiguration.admit(kast, root, environment, sink) as Refinement.Refined).value
    }

    private fun privateOptions(kast: Path, root: Path): InstalledCoordinatorOptions =
        (InstalledCoordinatorConfiguration.admit(kast, root, mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private"))
                as Refinement.Refined)
            .value

    private fun withPayload(test: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-c-").toRealPath()
        try {
            for (directory in listOf("bin", "lib", "share")) Files.createDirectory(root.resolve(directory))
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            test(root, kast)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
