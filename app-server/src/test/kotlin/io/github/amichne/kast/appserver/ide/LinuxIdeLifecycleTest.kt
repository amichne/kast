package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class LinuxIdeLifecycleTest {
    @Serializable private data class Product(val buildNumber: String, val launch: List<Launch>)

    @Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)

    private fun platform(
        display: String?,
        start: (List<String>) -> Process = { error("Unexpected launch") },
    ): IdeLaunchPlatform =
        object : IdeLaunchPlatform {
            override fun display(): String? = display

            override fun start(arguments: List<String>): Process = start(arguments)

            override fun processes(): List<IdeProcessObservation> = emptyList()
        }

    private fun target(root: Path): SelectedIdeLaunch.Resolved {
        val home = Files.createDirectory(root.resolve("IDE with spaces")).toRealPath()
        Files.createDirectory(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea"), "must never execute")
        home.resolve("bin/idea").toFile().setExecutable(true)
        Files.writeString(
            home.resolve("product-info.json"),
            Json.encodeToString(Product("262.1", listOf(Launch("Linux", "amd64", "bin/idea")))),
        )
        return assertInstanceOf<SelectedIdeLaunch.Resolved>(SelectedIdeInstallation.resolve(home))
    }

    @Test
    fun `missing display fails before launching the admitted executable`(@TempDir root: Path) {
        val target = target(root)
        val client =
            IdeLifecycleClient(
                userHome = root,
                selectedHome = Path.of(target.home),
                exchangeTimer = LifecycleExchangeTimer.Native,
                nanoTime = System::nanoTime,
                platform = platform(null),
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLATFORM_UNAVAILABLE),
            client.open(root.toString(), "request", "test"),
        )
        assertEquals(listOf(target.executable, "nosplash", "dontReopenProjects"), ideLaunchArguments(target))
    }

    @Test
    fun `early application exit is a launch failure before the handshake timeout`(@TempDir root: Path) {
        val target = target(root)
        var arguments: List<String> = emptyList()
        val client =
            IdeLifecycleClient(
                userHome = root,
                selectedHome = Path.of(target.home),
                exchangeTimer = LifecycleExchangeTimer.Native,
                nanoTime = System::nanoTime,
                platform =
                    platform(":99") { supplied ->
                        arguments = supplied
                        ProcessBuilder("/usr/bin/false").start().also { it.waitFor() }
                    },
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.LAUNCH_FAILED),
            client.open(root.toString(), "request", "test"),
        )
        assertEquals(listOf(target.executable, "nosplash", "dontReopenProjects"), arguments)
    }

    @Test
    fun `process ownership needs the selected executable or exact JBR home argument`(@TempDir root: Path) {
        val target = target(root)
        val java = target.bundledJavaHome.resolve("bin/java").toString()
        val exact = "-Didea.home.path=${target.home}"
        assertEquals(
            LinuxIdeProcessMatch.SELECTED,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("owner", target.executable, emptyList())),
        )
        assertEquals(
            LinuxIdeProcessMatch.SELECTED,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("owner", java, listOf(exact))),
        )
        assertEquals(
            LinuxIdeProcessMatch.OTHER,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("other", target.executable, null)),
        )
        assertEquals(
            LinuxIdeProcessMatch.OTHER,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("owner", "/other/java", null)),
        )
        assertEquals(
            LinuxIdeProcessMatch.OTHER,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("owner", java, listOf(exact + "/other"))),
        )
        assertEquals(
            LinuxIdeProcessMatch.OTHER,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("owner", java, emptyList())),
        )
        assertEquals(
            LinuxIdeProcessMatch.UNAVAILABLE,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation("owner", java, null)),
        )
        assertEquals(
            LinuxIdeProcessMatch.UNAVAILABLE,
            matchLinuxIdeProcess(target, "owner", IdeProcessObservation(null, null, null)),
        )
    }
}
