package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SelectedIdeInstallationTest {
    @Serializable
    private data class Product(
        val buildNumber: String,
        val launch: List<Launch>,
        val dataDirectoryName: String = "IntelliJIdea2026.2",
    )

    @Serializable
    private data class Launch(val os: String = "macOS", val arch: String = "aarch64", val launcherPath: String)

    private fun fixture(root: Path, build: String, launchers: List<String>): Path {
        val home = Files.createDirectories(root.resolve("Selected.app/Contents")).toRealPath()
        Files.createDirectories(home.resolve("Resources"))
        Files.writeString(
            home.resolve("Resources/product-info.json"),
            Json { encodeDefaults = true }.encodeToString(Product(build, launchers.map { Launch(launcherPath = it) })),
        )
        Files.createDirectories(home.resolve("MacOS"))
        Files.writeString(home.resolve("MacOS/real-idea"), "")
        home.resolve("MacOS/real-idea").toFile().setExecutable(true)
        return home
    }

    @Test
    fun `launch target follows metadata across supported patch updates`(@TempDir root: Path) {
        val home = fixture(root, "262.1234.99", listOf("../MacOS/real-idea"))
        val target = assertInstanceOf(SelectedIdeLaunch.Resolved::class.java, SelectedIdeInstallation.resolve(home))
        assertEquals(home.resolve("MacOS/real-idea").toString(), target.executable)
        assertEquals(home.parent.toString(), target.bundle)
        fixture(root, "262.9999.1", listOf("../MacOS/real-idea"))
        assertInstanceOf(SelectedIdeLaunch.Resolved::class.java, SelectedIdeInstallation.resolve(home))
    }

    @Test
    fun `plugin profile comes from the exact admitted IDE metadata`(@TempDir root: Path) {
        val home = fixture(root, "262.1234.99", listOf("../MacOS/real-idea"))
        val user = Files.createDirectory(root.resolve("user")).toRealPath()
        val launch = assertInstanceOf(SelectedIdeLaunch.Resolved::class.java, SelectedIdeInstallation.resolve(home))
        val admitted = SelectedIdeInstallation.pluginRoot(launch, user)
        val plugin = org.junit.jupiter.api.assertInstanceOf<Refinement.Refined<SelectedIdePluginRoot>>(admitted).value
        assertEquals(user.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins"), plugin.value)
        Files.writeString(
            home.resolve("Resources/product-info.json"),
            Json { encodeDefaults = true }
                .encodeToString(Product("262.1234.99", listOf(Launch(launcherPath = "../MacOS/real-idea")), "..")),
        )
        assertEquals(
            Refinement.Rejected(IdePluginRootFailure.INVALID_METADATA),
            SelectedIdeInstallation.pluginRoot(launch, user),
        )
    }

    @Test
    fun `recorded Host target retains its identity independently of current launch metadata`(@TempDir root: Path) {
        val home = fixture(root, "262.1234.99", listOf("../MacOS/real-idea"))
        val user = Files.createDirectory(root.resolve("user")).toRealPath()
        val target = user.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins")
        val admitted =
            org.junit.jupiter.api
                .assertInstanceOf<Refinement.Refined<InstalledHostPluginTarget>>(
                    SelectedIdeInstallation.admitPluginTarget(target.toString(), user)
                )
                .value as InstalledHostPluginTarget.Recorded
        val launch =
            assertInstanceOf(
                SelectedIdeLaunch.Resolved::class.java,
                SelectedIdeInstallation.recordInstalledHostTarget(SelectedIdeInstallation.resolve(home), admitted),
            )
        assertEquals(admitted, launch.hostPluginTarget)
        Files.writeString(
            home.resolve("Resources/product-info.json"),
            Json { encodeDefaults = true }
                .encodeToString(
                    Product("262.9999.1", listOf(Launch(launcherPath = "../MacOS/real-idea")), "IntelliJIdeaChanged")
                ),
        )
        assertTrue(SelectedIdeInstallation.matchesCurrentLaunch(launch))
        assertEquals(
            target,
            org.junit.jupiter.api
                .assertInstanceOf<Refinement.Refined<SelectedIdePluginRoot>>(
                    SelectedIdeInstallation.recordedPluginRoot(admitted, user)
                )
                .value
                .value,
        )
    }

    @Test
    fun `unsupported line and ambiguous launchers fail closed`(@TempDir root: Path) {
        val home = fixture(root, "263.1", listOf("../MacOS/real-idea"))
        assertEquals(
            SelectedIdeLaunch.Unavailable(IdeLaunchFailure.UNSUPPORTED_PLATFORM_LINE),
            SelectedIdeInstallation.resolve(home),
        )
        fixture(root, "262.1", listOf("../MacOS/real-idea", "../MacOS/other"))
        assertEquals(
            SelectedIdeLaunch.Unavailable(IdeLaunchFailure.AMBIGUOUS_LAUNCHER),
            SelectedIdeInstallation.resolve(home),
        )
    }

    @Test
    fun `missing and escaping executables cannot be launched`(@TempDir root: Path) {
        val home = fixture(root, "262.1", listOf("../../outside"))
        assertEquals(
            SelectedIdeLaunch.Unavailable(IdeLaunchFailure.INVALID_LAUNCHER),
            SelectedIdeInstallation.resolve(home),
        )
        fixture(root, "262.1", listOf("../MacOS/missing"))
        assertEquals(
            SelectedIdeLaunch.Unavailable(IdeLaunchFailure.EXECUTABLE_UNAVAILABLE),
            SelectedIdeInstallation.resolve(home),
        )
    }
}
