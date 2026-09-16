package io.github.amichne.kast.distribution.managed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SelectedIdeInstallationTest {
    @Serializable private data class Product(val buildNumber: String, val launch: List<Launch>)

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
