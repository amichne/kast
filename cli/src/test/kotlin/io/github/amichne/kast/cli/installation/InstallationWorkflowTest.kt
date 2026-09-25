package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationWorkflowTest {
    @Test
    fun `installation child logs bounded success and rejection outcomes`() {
        val observations = mutableListOf<InstallationChildObservation>()
        for ((executable, expected) in
            listOf(
                "/usr/bin/true" to InstallationChildOutcome.COMPLETED,
                "/usr/bin/false" to InstallationChildOutcome.EXIT_REJECTED,
                "/missing-secret-installation-command" to InstallationChildOutcome.IO_REJECTED,
            )) {
            assertEquals(
                expected,
                executeInstallationChild(
                    InstallationChildStage.PRIOR_RETIREMENT,
                    listOf(executable),
                    mapOf("PRIVATE_SETTING" to "secret-input"),
                    observations::add,
                ),
            )
        }
        assertEquals(3, observations.size)
        assertEquals(
            """{"event":"kast_installation","stage":"PRIOR_RETIREMENT","outcome":"IO_REJECTED"}""",
            observations.last().toJson(),
        )
        assertTrue(observations.none { "secret" in it.toJson() })
    }

    @Test
    fun `fresh installation materializes every saved default in its environment file`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(
                releaseRequest(
                    root,
                    installation,
                    root.resolve("commands"),
                    Files.createDirectory(root.resolve("home")),
                    Files.createDirectory(root.resolve("codex-home")),
                    "1.2.3",
                )
            ),
        )
        val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val values =
            Files.readAllLines(selected.resolve("config/environment"))
                .filterNot { it.startsWith("#") }
                .filter(String::isNotBlank)
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        val defaults =
            KastConfigurationCatalogue.declarations.filter {
                ConfigurationSource.SAVED_INSTALLATION in it.sources && it.defaultValue != null
            }
        defaults.forEach { declaration ->
            assertTrue(values.containsKey(declaration.key), declaration.key)
        }
        assertEquals("0", values["KAST_DEBUG"])
        assertLauncherSelection(selected, root, installation)
    }

    private fun assertLauncherSelection(selected: Path, root: Path, installation: Path) {
        Files.writeString(
            selected.resolve("bin/kast"),
            "#!/bin/sh\n" +
                "printf '%s\\n' \"${'$'}KAST_CONFIGURATION_FILE\" \"${'$'}{KAST_SAVED_CONFIGURATION_FAILURE-}\"\n",
        )
        val launcher = selected.resolve("bin/kast-complete").toString()
        val alternate = root.resolve("alternate-environment").toString()
        val inherited = installation.resolve("versions/prior/config/environment").toString()
        for (selector in listOf(null, alternate, inherited)) {
            val process =
                ProcessBuilder(launcher)
                    .apply {
                        environment().remove("KAST_CONFIGURATION_FILE")
                        environment().remove("KAST_SAVED_CONFIGURATION_FAILURE")
                        if (selector != null) environment()["KAST_CONFIGURATION_FILE"] = selector
                    }
                    .start()
            val lines = process.inputStream.bufferedReader().readLines()
            assertEquals(0, process.waitFor())
            assertEquals(listOf(selector ?: selected.resolve("config/environment").toString(), ""), lines)
        }
    }

    @Test
    fun `force reinstall resets same version state and preserves unrelated files`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val environment =
            installationEnvironment(
                releaseFixture(root, "1.2.3", 5, 0),
                "1.2.3",
                installation,
                root.resolve("commands"),
                Files.createDirectory(root.resolve("home")),
                Files.createDirectory(root.resolve("codex")),
                InstallationMode.APPLY,
            )
        fun request(overrides: Map<String, String> = emptyMap()): InstallationRequest =
            (InstallationRequest.parse(environment + overrides) as Refinement.Refined).value
        val request = request()
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(request))
        val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val run = Files.createDirectories(selected.resolve("state/run"))
        val stale = Files.writeString(run.resolve("c.sock"), "stale socket")
        Files.writeString(selected.resolve("config/workspaces.json"), "broken registry")
        val unrelated = Files.writeString(root.resolve("keep"), "keep")
        val forced = request(mapOf("KAST_INSTALL_FORCE" to "1"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(request(mapOf("KAST_INSTALL_FORCE" to "1", "KAST_INSTALL_MODE" to "plan"))),
        )
        assertEquals("stale socket", Files.readString(stale))
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(forced))
        assertTrue(Files.notExists(stale))
        assertTrue(Files.notExists(selected.resolve("config/workspaces.json")))
        assertEquals("keep", Files.readString(unrelated))
        assertTrue(Files.notExists(root.resolve("commands/kast")))
        assertTrue(Files.notExists(selected.resolve(".recovery-detached")))
    }

    @Test
    fun `upgrade retains the admitted workspace registry`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(home.resolve(".codex"))
        val firstWorkspace = Files.createDirectory(root.resolve("first-workspace"))
        val secondWorkspace = Files.createDirectory(root.resolve("second-workspace"))

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val registry =
            Json.encodeToString(RegistryFixture(2, 2, listOf(firstWorkspace.toString(), secondWorkspace.toString())))
        Files.writeString(prior.resolve("config/workspaces.json"), registry)

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.4")),
        )

        val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        assertEquals(registry, Files.readString(selected.resolve("config/workspaces.json")))
    }

    @Test
    fun `upgrade rejects a corrupt prior registry and preserves selection`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        val home = Files.createDirectory(root.resolve("home"))
        val codex = Files.createDirectory(root.resolve("codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.3")),
        )
        val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        Files.writeString(prior.resolve("config/workspaces.json"), "broken registry")
        val rejected =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.4")),
            )
        assertEquals(InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED, rejected.failure)
        assertEquals("broken registry", Files.readString(prior.resolve("config/workspaces.json")))
        val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        assertEquals(prior, selected)
    }

    @Test
    fun `prior retirement reconstructs only historical enabled owner overrides`() {
        val prior = Path.of("/fixture/versions/1.2.3-payload")
        for (saved in listOf("", "KAST_ENABLE_APP_SERVER=0\n", "KAST_ENABLE_APP_SERVER=1\n")) {
            val configuration = (SavedConfigurationDocument.parse(saved.toByteArray()) as Refinement.Refined).value
            val baseline =
                mapOf(
                    "HOME" to "/fixture/home",
                    "PATH" to "/usr/bin:/bin",
                    "CODEX_HOME" to "/fixture/codex",
                    "KAST_CONFIGURATION_FILE" to "/fixture/versions/1.2.3-payload/config/environment",
                )
            val expected = if (saved.isEmpty()) baseline else baseline + ("KAST_ENABLE_APP_SERVER" to "1")
            assertEquals(
                expected,
                priorServiceRetirementEnvironment(
                    prior,
                    Path.of("/fixture/home"),
                    Path.of("/fixture/codex"),
                    "/usr/bin:/bin",
                    configuration,
                ),
            )
        }
    }

    @Test
    fun `owned legacy activation lock is narrowed before installation proceeds`(@TempDir temporary: Path) {
        val lock = Files.writeString(temporary.resolve("activation.lock"), "")
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"))

        assertTrue(secureActivationLock(lock))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(lock),
        )
    }

    @Test
    fun `unrelated commands survive installation without force`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = Files.createDirectory(root.resolve("commands"))
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(home.resolve(".codex"))
        val foreign = Files.writeString(commands.resolve("kast"), "unmanaged")

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        assertEquals("unmanaged", Files.readString(foreign))
        assertTrue(Files.notExists(commands.resolve("kast-codex")))
    }

    @Test
    fun `upgrade retires only the prior Kast command links`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = Files.createDirectory(root.resolve("commands"))
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(home.resolve(".codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        Files.writeString(
            prior.resolve("config/workspaces.json"),
            Json.encodeToString(RegistryFixture(2, 0, emptyList())),
        )
        for ((name, executable) in listOf("kast" to "kast-complete", "kast-codex" to "kast-codex-complete")) {
            Files.createSymbolicLink(commands.resolve(name), installation.resolve("current/bin/$executable"))
        }

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.4")),
        )
        assertTrue(Files.notExists(commands.resolve("kast")))
        assertTrue(Files.notExists(commands.resolve("kast-codex")))
    }
}

/** Fixture-owned installations have no launchd job or published daemon state. */
internal fun executeFixtureInstallation(request: InstallationRequest): InstallationOutcome =
    InstallationWorkflow.execute(
        request,
        PriorDaemonUpgradeGateway { retirement, _, _ ->
            val prior = retirement.executable.parent.parent
            check(Files.notExists(prior.resolve("state/broker/service.plist")))
            check(Files.notExists(prior.resolve("state/broker/service-readiness.json")))
            io.github.amichne.kast.appserver.InstalledUpgradePreparation.NoDaemon
        },
    )

internal fun releaseRequest(
    fixture: Path,
    installation: Path,
    commands: Path,
    home: Path,
    codexHome: Path,
    version: String,
    controlFileCount: Int = 5,
    mode: InstallationMode = InstallationMode.APPLY,
    lifecycleInspectionExit: Int = 0,
    replaceCommandCollisions: Boolean = false,
    environmentOverrides: Map<String, String> = emptyMap(),
): InstallationRequest {
    val product = releaseFixture(fixture, version, controlFileCount, lifecycleInspectionExit)
    val parsed =
        InstallationRequest.parse(
            installationEnvironment(
                product,
                version,
                installation,
                commands,
                home,
                codexHome,
                mode,
                replaceCommandCollisions,
            ) + environmentOverrides
        )
    return when (parsed) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("fixture request rejected: ${parsed.failure}")
    }
}

private fun releaseFixture(
    fixture: Path,
    version: String,
    controlFileCount: Int,
    lifecycleInspectionExit: Int,
): ReleaseFixture {
    val control = Files.createDirectories(fixture.resolve("control-$version"))
    val metadata = Files.createDirectories(control.resolve("share/kast"))
    writeControlFiles(control, metadata, controlFileCount, lifecycleInspectionExit)
    val runtime = writePluginFixture(fixture, metadata, version)
    val controlArchive = Files.writeString(fixture.resolve("control-$version.tar.gz"), "control-$version")
    val idea = writeIdeaFixture(fixture)
    return ReleaseFixture(control, controlArchive, runtime, digest(runtime), idea.first, idea.second)
}

private fun writeControlFiles(
    control: Path,
    metadata: Path,
    controlFileCount: Int,
    lifecycleInspectionExit: Int,
) {
    Files.createDirectories(control.resolve("lib"))
    val bin = Files.createDirectories(control.resolve("bin"))
    Files.copy(Path.of("../packaging/installation-recovery.py"), metadata.resolve("installation-recovery.py"))
    val emptyDocument = Json.encodeToString(EmptyDocumentFixture)
    val executable = Files.writeString(bin.resolve("kast"), "#!/bin/sh\nexit 0\n")
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"))
    Files.copy(executable, bin.resolve("kast-codex"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Files.copy(executable, bin.resolve("kast-mcp"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Files.copy(executable, bin.resolve("kast-tool-rpc"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Files.copy(Path.of("../packaging/codex-mcp-registration.py"), metadata.resolve("codex-mcp-registration.py"))
    Files.writeString(metadata.resolve("operation-registry.json"), emptyDocument)
    Files.writeString(metadata.resolve("wire-schema.json"), emptyDocument)
    Files.writeString(
        metadata.resolve("installation-lifecycle.py"),
        """
            |import json
            |from pathlib import Path
            |import sys
            |if $lifecycleInspectionExit:
            |    raise SystemExit($lifecycleInspectionExit)
            |arguments = sys.argv[1:]
            |installation = Path(arguments[arguments.index('--installation') + 1])
            |manifest = json.loads((installation / 'installation.json').read_text())
            |registry = Path(manifest['workspaceRegistry'])
            |document = json.loads(registry.read_text())
            |if set(document) != {'schemaVersion', 'revision', 'roots'} or document['schemaVersion'] != 2:
            |    raise SystemExit(1)
            |print('{"status":"complete"}')
            |"""
            .trimMargin(),
    )
    require(controlFileCount >= 5)
    val knowledge = Files.createDirectories(metadata.resolve("knowledge/declarations"))
    repeat(controlFileCount - 5) { index -> Files.writeString(knowledge.resolve("$index.json"), emptyDocument) }
}

private fun writePluginFixture(fixture: Path, metadata: Path, version: String): Path {
    val runtime = fixture.resolve("kast-ide-hosted-$version.zip")
    ZipOutputStream(Files.newOutputStream(runtime)).use { archive ->
        archive.putNextEntry(ZipEntry("kast-ide-hosted/lib/kast-ide-hosted.jar"))
        archive.write("fixture".toByteArray())
        archive.closeEntry()
    }
    val runtimeDigest = digest(runtime)
    Files.writeString(
        metadata.resolve("ide-host.json"),
        Json.encodeToString(
            PluginFixture(
                1,
                version,
                "existing_ide",
                "261.1",
                "261.1-IJ",
                runtime.fileName.toString(),
                "sha256:$runtimeDigest",
                Files.size(runtime),
            )
        ),
    )
    return runtime
}

private fun writeIdeaFixture(fixture: Path): Pair<Path, Path> {
    val idea = Files.createDirectories(fixture.resolve("idea"))
    Files.createDirectories(idea.resolve("Resources"))
    Files.createDirectories(idea.resolve("plugins/Kotlin"))
    val javaHome = Files.createDirectories(idea.resolve("jbr/Contents/Home"))
    val java = Files.createDirectories(javaHome.resolve("bin")).resolve("java")
    if (!Files.exists(java)) {
        Files.writeString(java, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
    Files.writeString(idea.resolve("Resources/build.txt"), "IU-261.1")
    return idea to javaHome
}

private fun installationEnvironment(
    product: ReleaseFixture,
    version: String,
    installation: Path,
    commands: Path,
    home: Path,
    codexHome: Path,
    mode: InstallationMode,
    replaceCommandCollisions: Boolean = false,
): Map<String, String> =
    mapOf(
        InstallationEnvironment.CONTROL_ROOT.key to product.controlRoot.toString(),
        InstallationEnvironment.CONTROL_ARCHIVE.key to product.controlArchive.toString(),
        InstallationEnvironment.CONTROL_SHA256.key to digest(product.controlArchive),
        InstallationEnvironment.HOSTED_PLUGIN_ARCHIVE.key to product.pluginArchive.toString(),
        InstallationEnvironment.HOSTED_PLUGIN_SHA256.key to product.pluginDigest,
        InstallationEnvironment.VERSION.key to version,
        InstallationEnvironment.IDEA_HOME.key to product.ideaHome.toString(),
        InstallationEnvironment.JAVA_HOME.key to product.javaHome.toString(),
        InstallationEnvironment.INSTALL_ROOT.key to installation.toString(),
        InstallationEnvironment.BIN_DIRECTORY.key to commands.toString(),
        InstallationEnvironment.HOME.key to home.toString(),
        InstallationEnvironment.CODEX_HOME.key to codexHome.toString(),
        InstallationEnvironment.PROFILE.key to "session",
        InstallationEnvironment.MODE.key to mode.name.lowercase(),
        InstallationEnvironment.FORCE.key to if (replaceCommandCollisions) "1" else "0",
    )

private data class ReleaseFixture(
    val controlRoot: Path,
    val controlArchive: Path,
    val pluginArchive: Path,
    val pluginDigest: String,
    val ideaHome: Path,
    val javaHome: Path,
)

private fun digest(path: Path): String = Files.newInputStream(path).use(::digest)

private fun digest(input: java.io.InputStream): String = input.use {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = it.read(buffer)
        if (count < 0) break
        hash.update(buffer, 0, count)
    }
    hash.digest().joinToString("") { byte -> "%02x".format(byte) }
}

@Serializable private data object EmptyDocumentFixture

@Serializable
private data class PluginFixture(
    val schemaVersion: Int,
    val productVersion: String,
    val execution: String,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val fileName: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable internal data class RegistryFixture(val schemaVersion: Int, val revision: Int, val roots: List<String>)
