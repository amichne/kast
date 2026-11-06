package io.github.amichne.kast.distribution.managed

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
            assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(ManagedHeadlessLaunch.prepare(installation))
                .value
        assertEquals(listOf(fixture.launch.executable, "kast-headless"), specification.arguments)
        org.junit.jupiter.api.Assertions.assertTrue("-Dvendor.required=true\n" in specification.options)
        org.junit.jupiter.api.Assertions.assertFalse("-Xmx2048m" in specification.options)
        assertEquals(installation.userHome.toString(), specification.environment["HOME"])
        assertEquals(installation.launch.bundledJavaHome.toString(), specification.environment["IDEA_JDK"])
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

    private data class Fixture(
        val root: Path,
        val state: Path,
        val launch: SelectedIdeLaunch.Resolved,
        val plugins: Path,
        val bootstrap: Path,
        val manifest: Path,
    )

    private fun fixture(directory: Path): Fixture {
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
                    "262.10315.125",
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
                "262.10315.125",
                "262.10315.125-IJ",
            )
        val manifest = installation.resolve("kast-headless.json")
        Files.writeString(manifest, Json.encodeToString(record))
        return Fixture(root, state, launch, plugins, bootstrap, manifest)
    }
}
