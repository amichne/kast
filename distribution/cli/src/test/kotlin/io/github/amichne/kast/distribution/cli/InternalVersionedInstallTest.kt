package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.IdeLaunchFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class InternalVersionedInstallTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `both declared IDE lines require the actual vendor launcher and derived profile without execution`() {
        for ((line, profile) in listOf("262" to "IntelliJIdea2026.2", "263" to "IntelliJIdea2026.3")) {
            val home = Files.createDirectories(directory.resolve("home-$line")).toRealPath()
            val idea = vendor(line, profile)
            val admitted =
                assertInstanceOf<InstallerIdeAdmission.Admitted>(
                    admitInstallerIde(
                        mapOf(
                            "HOME" to home.toString(),
                            "KAST_INSTALL_IDEA_HOME" to idea.toString(),
                        )
                    )
                )
            assertEquals(idea.toString(), admitted.launch.home)
            assertEquals(idea.resolve("MacOS/idea").toString(), admitted.launch.executable)
            assertEquals(
                home.resolve("Library/Application Support/JetBrains/$profile/plugins"),
                admitted.pluginRoot.value,
            )
            assertFalse(Files.exists(home.resolve("Library")))
        }
    }

    @Test
    fun `unknown vendor line and escaping profile remain typed rejections`() {
        val home = Files.createDirectories(directory.resolve("home")).toRealPath()
        val future = vendor("264", "IntelliJIdea2027.1")
        assertEquals(
            InstallerIdeAdmission.LaunchRejected(IdeLaunchFailure.UNSUPPORTED_PLATFORM_LINE),
            admitInstallerIde(mapOf("HOME" to home.toString(), "KAST_INSTALL_IDEA_HOME" to future.toString())),
        )
        val invalid = vendor("263", "..")
        assertInstanceOf<InstallerIdeAdmission.ProfileRejected>(
            admitInstallerIde(mapOf("HOME" to home.toString(), "KAST_INSTALL_IDEA_HOME" to invalid.toString()))
        )
        assertFalse(Files.exists(home.resolve("Library")))
    }

    private fun vendor(line: String, profile: String): Path {
        val idea = Files.createDirectories(directory.resolve("Selected$line.app/Contents")).toRealPath()
        Files.createDirectories(idea.resolve("Resources"))
        Files.createDirectories(idea.resolve("MacOS"))
        Files.writeString(idea.resolve("MacOS/idea"), "fixture launcher must never execute")
        check(idea.resolve("MacOS/idea").toFile().setExecutable(true))
        Files.writeString(
            idea.resolve("Resources/product-info.json"),
            managementJson.encodeToString(Vendor("$line.123.45", profile, listOf(Launch()))),
        )
        return idea
    }

    @Test
    fun `malformed or relative captured selection paths reject finitely before publication`() {
        val home = Files.createDirectories(directory.resolve("home")).toRealPath()
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        for (selected in listOf("\u0000", "relative/selection.json")) {
            val failure =
                assertThrows<ManagementRejected> {
                    internalInstall(
                        listOf("--internal-install", "commit-selected-active"),
                        mapOf(
                            "HOME" to home.toString(),
                            "KAST_INSTALL_ROOT" to root.toString(),
                            "KAST_MANAGEMENT_CHANNEL" to "developer",
                            "KAST_MANAGEMENT_SELECTION" to selected,
                        ),
                    )
                }
            assertEquals("managed-publication", failure.stage)
            assertEquals("selection invalid", failure.reason)
            assertFalse(Files.exists(root.resolve("management.json")))
            assertFalse(Files.exists(home.resolve(".local/bin/kast")))
        }
    }

    @Test
    fun `selected IDEA preflight requires an explicit target before any installation effect`() {
        val home = Files.createDirectories(directory.resolve("home")).toRealPath()
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val sentinel = Files.writeString(root.resolve("sentinel"), "retained")
        val failure =
            assertThrows<ManagementRejected> {
                internalInstall(
                    listOf("--internal-install", "preflight-idea"),
                    mapOf("HOME" to home.toString(), "KAST_INSTALL_ROOT" to root.toString()),
                )
            }
        assertEquals("installer-idea-preflight", failure.stage)
        assertEquals("IDEA_HOME_UNAVAILABLE", failure.reason)
        assertEquals("retained", Files.readString(sentinel))
        assertFalse(Files.exists(root.resolve("management.json")))
    }

    @Test
    fun `selected active completion requires the captured binding before public command publication`() {
        val home = Files.createDirectories(directory.resolve("home")).toRealPath()
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val command = home.resolve(".local/bin/kast")
        Files.createDirectories(command.parent)
        Files.writeString(command, "retained command")
        val failure =
            assertThrows<ManagementRejected> {
                internalInstall(
                    listOf("--internal-install", "commit-selected-active"),
                    mapOf(
                        "HOME" to home.toString(),
                        "KAST_INSTALL_ROOT" to root.toString(),
                        "KAST_MANAGEMENT_CHANNEL" to "developer",
                    ),
                )
            }
        assertEquals("managed-publication", failure.stage)
        assertEquals("selection unavailable", failure.reason)
        assertEquals("retained command", Files.readString(command))
        assertFalse(Files.exists(root.resolve("management.json")))
    }
}

@Serializable
private data class Vendor(val buildNumber: String, val dataDirectoryName: String, val launch: List<Launch>)

@Serializable
private data class Launch(
    val os: String = "macOS",
    val arch: String = "aarch64",
    val launcherPath: String = "../MacOS/idea",
)
