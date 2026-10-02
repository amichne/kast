package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.HostedServiceObservation
import io.github.amichne.kast.distribution.contract.HostedRegistryFailure
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RegisteredHostedServicesTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `registry rejection preserves each finite cause without observing a fabricated host`() {
        val causes =
            listOf(
                EnrollmentFailure.PATH_REJECTED to HostedRegistryFailure.PATH_REJECTED,
                EnrollmentFailure.DOCUMENT_REJECTED to HostedRegistryFailure.DOCUMENT_REJECTED,
                EnrollmentFailure.WRITE_REJECTED to HostedRegistryFailure.WRITE_REJECTED,
                EnrollmentFailure.WORKSPACE_CONFLICT to HostedRegistryFailure.WORKSPACE_CONFLICT,
                EnrollmentFailure.CAPACITY_EXCEEDED to HostedRegistryFailure.CAPACITY_EXCEEDED,
            )
        val registryPath = Path.of("/installation/config/workspaces.json")
        for ((failure, expected) in causes) {
            assertEquals(
                listOf(HostedServiceStatus.RegistryUnavailable(registryPath.toString(), expected)),
                registeredHostedServices(WorkspaceRegistryRead.Rejected(failure), registryPath) {
                    error("registry rejection cannot observe a host")
                },
            )
        }
    }

    @Test
    fun `missing settings marker cannot rebind registered directory to its ancestor`() {
        val home = temporary.toRealPath()
        Files.writeString(home.resolve("settings.gradle.kts"), "")
        val root = Files.createDirectory(home.resolve("registered"))
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        val registryPath = home.resolve("config/workspaces.json")
        val store = WorkspaceEnrollmentStore(registryPath)
        assertEquals(true, store.enroll(root) is Refinement.Refined)
        val snapshot = store.snapshot()
        Files.delete(root.resolve("settings.gradle.kts"))
        assertEquals(
            listOf(
                HostedServiceStatus.Unavailable(root.toString(), HostedServiceUnavailableFailure.CONFIGURATION_REJECTED)
            ),
            registeredHostedServices(snapshot, registryPath) { roots ->
                assertEquals(emptyList<Any>(), roots)
                emptyList()
            },
        )
    }

    @Test
    fun `physical root moved after registry admission cannot follow its replacement alias`() {
        val home = temporary.toRealPath()
        val root = Files.createDirectory(home.resolve("registered"))
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        val registryPath = home.resolve("config/workspaces.json")
        val store = WorkspaceEnrollmentStore(registryPath)
        assertEquals(true, store.enroll(root) is Refinement.Refined)
        val snapshot = store.snapshot()
        val moved = Files.move(root, home.resolve("moved"))
        Files.createSymbolicLink(root, moved)
        assertEquals(
            listOf(
                HostedServiceStatus.Unavailable(root.toString(), HostedServiceUnavailableFailure.CONFIGURATION_REJECTED)
            ),
            registeredHostedServices(snapshot, registryPath) { roots ->
                assertEquals(emptyList<Any>(), roots)
                emptyList()
            },
        )
        assertEquals(
            listOf(
                HostedServiceStatus.RegistryUnavailable(registryPath.toString(), HostedRegistryFailure.PATH_REJECTED)
            ),
            registeredHostedServices(store.snapshot(), registryPath) {
                error("invalid registry cannot observe a host")
            },
        )
    }

    @Test
    fun `valid registered root retains finite unavailable host evidence without starting a workspace`() {
        val home = temporary.toRealPath()
        Files.writeString(home.resolve("settings.gradle.kts"), "")
        val registryPath = home.resolve("config/workspaces.json")
        val store = WorkspaceEnrollmentStore(registryPath)
        assertEquals(true, store.enroll(home) is Refinement.Refined)
        val before = Files.readAllBytes(registryPath).toList()
        var observations = 0
        assertEquals(
            listOf(HostedServiceStatus.Unavailable(home.toString(), HostedServiceUnavailableFailure.HOST_UNAVAILABLE)),
            registeredHostedServices(store.snapshot(), registryPath) { roots ->
                observations++
                assertEquals(listOf(home), roots.map { it.path })
                listOf(
                    HostedServiceObservation.Unavailable(
                        roots.single(),
                        io.github.amichne.kast.appserver.ide.ExistingIdeFailure.HOST_UNAVAILABLE,
                    )
                )
            },
        )
        assertEquals(1, observations)
        assertEquals(before, Files.readAllBytes(registryPath).toList())
    }
}
