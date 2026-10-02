package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.InstallationResetRequest
import io.github.amichne.kast.distribution.contract.InstallationResetStorage
import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationLifecycleFenceTest {
    @Test
    fun `exterior reset marker blocks persistent startup while installation is absent`(@TempDir temporary: Path) {
        val root = temporary.resolve("kast")
        val installation = root.resolve("installation")
        assertEquals(InstallationLifecycleStartAdmission.AVAILABLE, InstallationLifecycleFence.observe(installation))
        val marker = installationResetFence(root)
        Files.writeString(marker, "incomplete journal still fences")
        assertEquals(
            InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS,
            InstallationLifecycleFence.observe(installation),
        )
        Files.delete(marker)
        assertEquals(InstallationLifecycleStartAdmission.AVAILABLE, InstallationLifecycleFence.observe(installation))
    }

    @Test
    fun `activation requires the persisted intent and live exclusive reset lease`(@TempDir temporary: Path) {
        val root = temporary.resolve("kast")
        val installation = root.resolve("installation")
        val marker = installationResetFence(root)
        Files.writeString(
            marker,
            Json.encodeToString(InstallationResetRequest(installation.toString(), InstallationResetStorage.Activating)),
        )
        val lease = marker.resolveSibling(marker.fileName.toString() + ".lock")
        FileChannel.open(lease, CREATE, WRITE).use { channel ->
            assertEquals(
                InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS,
                InstallationLifecycleFence.observe(installation),
            )
            channel.lock().use {
                assertEquals(
                    InstallationLifecycleStartAdmission.AVAILABLE,
                    InstallationLifecycleFence.observe(installation),
                )
                Files.writeString(
                    marker,
                    Json.encodeToString(
                        InstallationResetRequest(installation.toString(), InstallationResetStorage.Erased)
                    ),
                )
                assertEquals(
                    InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS,
                    InstallationLifecycleFence.observe(installation),
                )
            }
            Files.writeString(
                marker,
                Json.encodeToString(
                    InstallationResetRequest(installation.toString(), InstallationResetStorage.Activating)
                ),
            )
            assertEquals(
                InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS,
                InstallationLifecycleFence.observe(installation),
            )
        }
    }

    @Test
    fun `foreign malformed and oversized activation records cannot authorize startup`(@TempDir temporary: Path) {
        val root = temporary.resolve("kast")
        val installation = root.resolve("installation")
        val marker = installationResetFence(root)
        val lease = marker.resolveSibling(marker.fileName.toString() + ".lock")
        FileChannel.open(lease, CREATE, WRITE).use { channel ->
            channel.lock().use {
                val records =
                    listOf(
                        "malformed",
                        " ".repeat(4097),
                        Json.encodeToString(
                            InstallationResetRequest("/foreign/installation", InstallationResetStorage.Activating)
                        ),
                    )
                records.forEach { record ->
                    Files.writeString(marker, record)
                    assertEquals(
                        InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS,
                        InstallationLifecycleFence.observe(installation),
                    )
                }
            }
        }
    }
}
