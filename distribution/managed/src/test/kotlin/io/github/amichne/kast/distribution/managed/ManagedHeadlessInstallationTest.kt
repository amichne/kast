package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.configuration.HostHeapPolicy
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class ManagedHeadlessInstallationTest {
    @Serializable
    private data class Product(val buildNumber: String, val dataDirectoryName: String, val launch: List<Launch>)

    @Serializable
    private data class Launch(val os: String, val arch: String, val launcherPath: String, val vmOptionsFilePath: String)

    @Test
    fun `only a selected published cache enters the managed launch`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val gradle = Files.createDirectory(fixture.root.resolve("shared"))
        val cache = gradle.resolve("kast-read-only")
        fun launchCache(): String? {
            val installation =
                assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                        ManagedHeadlessInstallation.resolve(
                            fixture.launch,
                            fixture.state,
                            fixture.state.resolve("home"),
                        )
                    )
                    .value
            return assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(
                    ManagedHeadlessLaunch.prepare(
                        installation,
                        "/usr/bin:/bin",
                        HostIdleShutdown.Disabled,
                        HostHeapPolicy.Inherit,
                    )
                )
                .value
                .environment["GRADLE_RO_DEP_CACHE"]
        }
        val record = fixture.state.resolve("gradle-home.json")
        Files.writeString(record, Json.encodeToString(ManagedHeadlessGradleHome(gradle.toString(), true)))
        assertEquals(null, launchCache())
        Files.createDirectories(cache.resolve("modules-2"))
        assertEquals(cache.toString(), launchCache())
        Files.writeString(record, Json.encodeToString(ManagedHeadlessGradleHome(gradle.toString())))
        assertEquals(null, launchCache())
    }

    @Test
    fun `private state admits exact assembly and rejects borrowed HOME`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val admitted =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                    ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home"))
                )
                .value
        assertEquals(fixture.launch, admitted.launch)
        assertEquals(fixture.state, admitted.state)
        assertEquals(fixture.plugins, admitted.plugins)
        assertEquals(
            Refinement.Rejected(ManagedHeadlessInstallationFailure.SHARED_STATE),
            ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.root),
        )
    }

    @Test
    fun `263 assembly preserves the platform record and rejects an unreviewed successor`(@TempDir directory: Path) {
        val fixture = fixture(directory, "263.7307.200")
        val actual =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                    ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home"))
                )
                .value
        assertEquals("263.7307.200", actual.assembly.platformBuild)
        assertEquals("263.7307.200-IJ", actual.assembly.kotlinPluginBuild)
        val original = Json.decodeFromString<ManagedHeadlessAssemblyRecord>(Files.readString(fixture.manifest))
        Files.writeString(
            fixture.manifest,
            Json.encodeToString(original.copy(platformBuild = "264.1.1", kotlinPluginBuild = "264.1.1-IJ")),
        )
        assertEquals(
            Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY),
            ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home")),
        )
    }

    @Test
    fun `changed bootstrap rejects ownership`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        Files.writeString(fixture.bootstrap, "changed")
        assertEquals(
            Refinement.Rejected(ManagedHeadlessInstallationFailure.BOOTSTRAP_CHANGED),
            ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home")),
        )
    }

    @Test
    fun `missing assembly never selects desktop`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        Files.delete(fixture.manifest)
        assertEquals(
            Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY),
            ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home")),
        )
    }

    @Test
    fun `private launch options preserve vendor flags and reject a borrowed destination`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val installation =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                    ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home"))
                )
                .value
        val specification =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(
                    ManagedHeadlessLaunch.prepare(
                        installation,
                        "/cloud/python/bin:/cloud/node/bin:/usr/bin:/bin",
                        HostIdleShutdown.Disabled,
                        HostHeapPolicy.Inherit,
                    )
                )
                .value
        assertEquals(listOf(fixture.launch.executable, "kast-headless"), specification.arguments)
        org.junit.jupiter.api.Assertions.assertTrue("-Dvendor.required=true\n" in specification.options)
        org.junit.jupiter.api.Assertions.assertTrue("-Djbr.dbus.enabled=false\n" in specification.options)
        assertEquals(listOf("-Xmx2048m"), specification.options.lines().filter { it.startsWith("-Xmx") })
        org.junit.jupiter.api.Assertions.assertFalse("kast.host.idle_shutdown_minutes" in specification.options)
        val idle =
            io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown.parseSetting("45")
                as Refinement.Refined
        val limited =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(
                    ManagedHeadlessLaunch.prepare(installation, "/usr/bin:/bin", idle.value, HostHeapPolicy.Inherit)
                )
                .value
        org.junit.jupiter.api.Assertions.assertTrue(
            limited.options.endsWith("-Dkast.host.idle_shutdown_minutes=45\n"),
            limited.options,
        )
        assertEquals(installation.userHome.toString(), specification.environment["HOME"])
        assertEquals(installation.launch.bundledJavaHome.toString(), specification.environment["IDEA_JDK"])
        assertEquals(
            "${installation.launch.bundledJavaHome}/bin:/cloud/python/bin:/cloud/node/bin:/usr/bin:/bin",
            specification.environment["PATH"],
        )
        org.junit.jupiter.api.Assertions.assertFalse("DISPLAY" in specification.environment)
        org.junit.jupiter.api.Assertions.assertFalse("JAVA_TOOL_OPTIONS" in specification.environment)
        val borrowed = Files.writeString(fixture.root.resolve("borrowed"), "protected")
        Files.createSymbolicLink(installation.options, borrowed)
        assertEquals(
            Refinement.Rejected(ManagedHeadlessLaunchFailure.WRITE_FAILED),
            ManagedHeadlessLaunchFiles.write(specification),
        )
        assertEquals("protected", Files.readString(borrowed))
    }

    @Test
    fun `Gradle delegation enters the child environment without entering launch arguments or options`(
        @TempDir directory: Path
    ) {
        val fixture = fixture(directory)
        val installation =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                    ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home"))
                )
                .value
        val delegated =
            assertInstanceOf<Refinement.Refined<GradleImportEnvironment>>(
                    GradleImportEnvironment.admit(
                        "REPO_TOKEN",
                        "",
                        mapOf("REPO_TOKEN" to "selected", "OTHER" to "hidden"),
                    )
                )
                .value
        val launch =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(
                    ManagedHeadlessLaunch.prepare(
                        installation,
                        null,
                        HostIdleShutdown.Disabled,
                        HostHeapPolicy.Inherit,
                        delegated,
                    )
                )
                .value

        assertEquals("selected", launch.environment["REPO_TOKEN"])
        org.junit.jupiter.api.Assertions.assertFalse("OTHER" in launch.environment)
        org.junit.jupiter.api.Assertions.assertFalse("selected" in launch.options)
        org.junit.jupiter.api.Assertions.assertFalse(launch.arguments.any { "selected" in it })
        org.junit.jupiter.api.Assertions.assertTrue(
            "-Dkast.gradle.import.environment.identity=${delegated.identity.value}" in launch.options.lines()
        )
    }

    @Test
    fun `heap selection renders one canonical option or rejects an ambiguous source`() {
        val bundled = listOf("-Xmx2048m")
        val custom = listOf("-Xms1024m", "-Dexample=true", "  -Xmx4G")
        val duplicate = listOf("-Xmx2048m", "-Xmx4096m")
        val configured =
            assertInstanceOf<Refinement.Refined<HostHeapPolicy.Explicit>>(HostHeapPolicy.Explicit.parseSetting("1536"))
                .value

        fun option(policy: HostHeapPolicy, custom: List<String>, bundled: List<String>): String =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessHeap>>(
                    ManagedHeadlessHeap.select(policy, custom, bundled)
                )
                .value
                .value
                .jvmOption

        assertEquals("-Xmx1536m", option(configured, duplicate, bundled))
        assertEquals("-Xmx4096m", option(HostHeapPolicy.Inherit, custom, bundled))
        assertEquals("-Xmx2048m", option(HostHeapPolicy.Inherit, listOf("-Xms1g"), bundled))
        assertEquals("-Xmx1536m", option(HostHeapPolicy.Inherit, emptyList(), emptyList()))
        assertEquals(
            Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA),
            ManagedHeadlessHeap.select(HostHeapPolicy.Inherit, duplicate, bundled),
        )
        assertEquals(
            Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA),
            ManagedHeadlessHeap.select(HostHeapPolicy.Inherit, emptyList(), duplicate),
        )
        assertEquals(
            Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA),
            ManagedHeadlessHeap.select(HostHeapPolicy.Inherit, listOf("-Xmxgarbage"), bundled),
        )
    }

    @Test
    fun `launch input deletes the previous exit record and fails closed when it cannot`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val installation =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                    ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home"))
                )
                .value
        val specification =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(
                    ManagedHeadlessLaunch.prepare(
                        installation,
                        "/usr/bin:/bin",
                        HostIdleShutdown.Disabled,
                        HostHeapPolicy.Inherit,
                    )
                )
                .value
        val previousExit = Files.writeString(installation.state.resolve("host-exit.json"), "previous launch")
        assertEquals(Refinement.Refined(Unit), ManagedHeadlessLaunchFiles.write(specification))
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(previousExit))
        Files.createDirectories(previousExit.resolve("held"))
        assertEquals(
            Refinement.Rejected(ManagedHeadlessLaunchFailure.WRITE_FAILED),
            ManagedHeadlessLaunchFiles.write(specification),
        )
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(previousExit.resolve("held")))
    }

    private data class Fixture(
        val root: Path,
        val state: Path,
        val launch: SelectedIdeLaunch.Resolved,
        val plugins: Path,
        val bootstrap: Path,
        val manifest: Path,
    )

    private fun fixture(directory: Path, build: String = "262.10315.125"): Fixture {
        val root = directory.toRealPath()
        val installation = Files.createDirectory(root.resolve("owned idea"))
        val state = Files.createDirectory(root.resolve("state"))
        for (name in listOf("home", "config", "system", "log", "tmp")) Files.createDirectory(state.resolve(name))
        Files.createDirectory(installation.resolve("bin"))
        Files.writeString(installation.resolve("bin/idea.vmoptions"), "-Dvendor.required=true\n-Xmx2048m\n")
        Files.writeString(installation.resolve("bin/idea"), "launcher fixture").toFile().setExecutable(true)
        Files.createDirectories(installation.resolve("jbr/bin"))
        Files.writeString(installation.resolve("jbr/bin/java"), "java fixture").toFile().setExecutable(true)
        Files.writeString(
            installation.resolve("product-info.json"),
            Json.encodeToString(
                Product(
                    build,
                    "IntelliJIdea2026.2",
                    listOf(Launch("Linux", "amd64", "bin/idea", "bin/idea.vmoptions")),
                )
            ),
        )
        val launch = assertInstanceOf<SelectedIdeLaunch.Resolved>(SelectedIdeInstallation.resolve(installation))
        val plugins = state.resolve("home/.local/share/JetBrains/IntelliJIdea2026.2/plugins")
        Files.createDirectories(plugins)
        val bootstrap = installation.resolve("third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar")
        Files.createDirectories(bootstrap.parent)
        Files.writeString(bootstrap, "bootstrap")
        val record =
            ManagedHeadlessAssemblyRecord(
                1,
                installation.toString(),
                "a".repeat(64),
                "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954",
                build,
                "$build-IJ",
            )
        val manifest = installation.resolve("kast-headless.json")
        Files.writeString(manifest, Json.encodeToString(record))
        return Fixture(root, state, launch, plugins, bootstrap, manifest)
    }
}
