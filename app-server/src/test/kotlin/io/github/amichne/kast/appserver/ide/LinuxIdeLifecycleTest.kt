package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.ManagedHeadlessAssemblyRecord
import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunch
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
    @Serializable
    private data class Product(
        val buildNumber: String,
        val launch: List<Launch>,
        val dataDirectoryName: String = "IntelliJIdea2026.2",
    )

    @Serializable
    private data class Launch(
        val os: String,
        val arch: String,
        val launcherPath: String,
        val vmOptionsFilePath: String = "bin/idea.vmoptions",
    )

    @Test
    fun `managed cold launch never enumerates desktop processes and early exit grants no readiness`(
        @TempDir directory: Path
    ) {
        val root = directory.toRealPath()
        val target = target(root)
        val state = ownedState(root, target)
        var launches = 0
        val platform =
            object : IdeLaunchPlatform {
                override fun display(): String? = error("Managed launch must not inspect the display")

                override fun start(arguments: List<String>): Process =
                    error("Managed launch must not use desktop launch")

                override fun processes(): List<IdeProcessObservation> =
                    error("Managed launch must not enumerate desktop processes")

                override fun startManaged(
                    specification: ManagedHeadlessLaunch
                ): io.github.amichne.kast.kernel.Refinement<Process, IdeLifecycleFailure> {
                    launches++
                    assertEquals(listOf(target.executable, "kast-headless"), specification.arguments)
                    assertEquals(state.resolve("home").toString(), specification.environment["HOME"])
                    return io.github.amichne.kast.kernel.Refinement.Refined(
                        ProcessBuilder("/usr/bin/false").start().also { it.waitFor() }
                    )
                }
            }
        val client =
            IdeLifecycleClient(
                state.resolve("home"),
                Path.of(target.home),
                LifecycleExchangeTimer.Native,
                System::nanoTime,
                platform,
                IdeLifecycleDeployment.Headless(state),
            )
        assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.LAUNCH_FAILED), client.startInstallation())
        assertEquals(1, launches)
    }

    private fun ownedState(root: Path, target: SelectedIdeLaunch.Resolved): Path {
        val state = Files.createDirectory(root.resolve("state"))
        for (name in listOf("home", "system", "log", "tmp")) Files.createDirectory(state.resolve(name))
        val home = Path.of(target.home)
        Files.writeString(home.resolve("bin/idea.vmoptions"), "-Dvendor.required=true\n")
        Files.createDirectories(home.resolve("jbr/bin"))
        Files.writeString(home.resolve("jbr/bin/java"), "java fixture").toFile().setExecutable(true)
        Files.createDirectories(state.resolve("home/.local/share/JetBrains/IntelliJIdea2026.2/plugins"))
        val bootstrap = home.resolve("third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar")
        Files.createDirectories(bootstrap.parent)
        Files.writeString(bootstrap, "bootstrap")
        Files.writeString(
            home.resolve("kast-headless.json"),
            Json.encodeToString(
                ManagedHeadlessAssemblyRecord(
                    1,
                    home.toString(),
                    "a".repeat(64),
                    "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954",
                    "262.10315.125",
                    "262.10315.125-IJ",
                )
            ),
        )
        return state
    }

    private fun platform(
        display: String?,
        start: (List<String>) -> Process = { error("Unexpected launch") },
    ): IdeLaunchPlatform =
        object : IdeLaunchPlatform {
            override fun display(): String? = display

            override fun start(arguments: List<String>): Process = start(arguments)

            override fun startManaged(
                specification: ManagedHeadlessLaunch
            ): io.github.amichne.kast.kernel.Refinement<Process, IdeLifecycleFailure> =
                error("Unexpected managed launch")

            override fun processes(): List<IdeProcessObservation> = emptyList()
        }

    private fun target(root: Path): SelectedIdeLaunch.Resolved {
        val home = Files.createDirectory(root.resolve("IDE with spaces")).toRealPath()
        Files.createDirectory(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea"), "must never execute")
        home.resolve("bin/idea").toFile().setExecutable(true)
        Files.writeString(
            home.resolve("product-info.json"),
            Json { encodeDefaults = true }
                .encodeToString(Product("262.10315.125", listOf(Launch("Linux", "amd64", "bin/idea")))),
        )
        return assertInstanceOf<SelectedIdeLaunch.Resolved>(SelectedIdeInstallation.resolve(home))
    }

    @Test
    fun `explicit managed selection rejects missing ownership before desktop discovery or launch`(@TempDir root: Path) {
        val target = target(root)
        val client =
            IdeLifecycleClient(
                root,
                Path.of(target.home),
                LifecycleExchangeTimer.Native,
                System::nanoTime,
                platform(":99"),
                IdeLifecycleDeployment.Headless(root.resolve("missing")),
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE),
            client.open(root.toString(), "request", "test"),
        )
        assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE), client.startInstallation())
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
