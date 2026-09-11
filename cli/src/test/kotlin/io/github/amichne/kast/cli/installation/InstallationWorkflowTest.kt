package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            InstallationWorkflow.execute(
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
            InstallationWorkflow.execute(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val registry = """{"schemaVersion":2,"revision":2,"roots":["$firstWorkspace","$secondWorkspace"]}"""
        Files.writeString(prior.resolve("config/workspaces.json"), registry)

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            InstallationWorkflow.execute(releaseRequest(root, installation, commands, home, codexHome, "1.2.4")),
        )

        val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        assertEquals(registry, Files.readString(selected.resolve("config/workspaces.json")))
    }

    @Test
    fun `prior service retirement reconstructs the enabled owner configuration`() {
        val prior = Path.of("/fixture/versions/1.2.3-payload")

        assertEquals(
            mapOf(
                "HOME" to "/fixture/home",
                "PATH" to "/usr/bin:/bin",
                "CODEX_HOME" to "/fixture/codex",
                "KAST_CONFIGURATION_FILE" to "/fixture/versions/1.2.3-payload/config/environment",
                "KAST_ENABLE_APP_SERVER" to "1",
            ),
            priorServiceRetirementEnvironment(
                prior = prior,
                home = Path.of("/fixture/home"),
                codexHome = Path.of("/fixture/codex"),
                path = "/usr/bin:/bin",
            ),
        )
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

    private fun releaseRequest(
        fixture: Path,
        installation: Path,
        commands: Path,
        home: Path,
        codexHome: Path,
        version: String,
    ): InstallationRequest {
        val control = Files.createDirectories(fixture.resolve("control-$version"))
        val bin = Files.createDirectories(control.resolve("bin"))
        val metadata = Files.createDirectories(control.resolve("share/kast"))
        val executable = Files.writeString(bin.resolve("kast"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.writeString(metadata.resolve("operation-registry.json"), "{}")
        Files.writeString(metadata.resolve("wire-schema.json"), "{}")
        Files.writeString(
            metadata.resolve("installation-lifecycle.py"),
            """
            |import json
            |from pathlib import Path
            |import sys
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

        val runtime = fixture.resolve("kast-semantic-runtime-$version-macos-aarch64.zip")
        ZipOutputStream(Files.newOutputStream(runtime)).use { archive ->
            archive.putNextEntry(ZipEntry("kast-indexer"))
            archive.write("fixture".toByteArray())
            archive.closeEntry()
        }
        val runtimeDigest = digest(runtime)
        val pluginDigest = "sha256:${"1".repeat(64)}"
        val archiveDigest = "sha256:$runtimeDigest"
        val runtimeIdentity =
            digest(
                listOf(
                        "macos",
                        "aarch64",
                        "261.1",
                        "2.4.10",
                        pluginDigest,
                        "kast-wire-v1",
                        archiveDigest,
                    )
                    .joinToString("\n")
                    .byteInputStream()
            )
        Files.writeString(
            metadata.resolve("semantic-runtime.json"),
            """{"schemaVersion":1,"runtimeId":"sha256:$runtimeIdentity","productVersion":"$version","platform":"macos","architecture":"aarch64","ideaBuild":"261.1","kotlinPluginBuild":"2.4.10","kastPluginSha256":"$pluginDigest","wireSchemaId":"kast-wire-v1","archive":{"fileName":"${runtime.fileName}","url":"https://example.invalid/${runtime.fileName}","sha256":"$archiveDigest","bytes":${Files.size(runtime)}},"layout":{"executable":"kast-indexer","requiredEntries":["kast-indexer"],"executableEntries":["kast-indexer"]}}""",
        )
        val controlArchive = Files.writeString(fixture.resolve("control-$version.tar.gz"), "control-$version")

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

        val parsed =
            InstallationRequest.parse(
                mapOf(
                    InstallationEnvironment.CONTROL_ROOT.key to control.toString(),
                    InstallationEnvironment.CONTROL_ARCHIVE.key to controlArchive.toString(),
                    InstallationEnvironment.CONTROL_SHA256.key to digest(controlArchive),
                    InstallationEnvironment.RUNTIME_ARCHIVE.key to runtime.toString(),
                    InstallationEnvironment.RUNTIME_SHA256.key to runtimeDigest,
                    InstallationEnvironment.VERSION.key to version,
                    InstallationEnvironment.IDEA_HOME.key to idea.toString(),
                    InstallationEnvironment.JAVA_HOME.key to javaHome.toString(),
                    InstallationEnvironment.INSTALL_ROOT.key to installation.toString(),
                    InstallationEnvironment.BIN_DIRECTORY.key to commands.toString(),
                    InstallationEnvironment.HOME.key to home.toString(),
                    InstallationEnvironment.CODEX_HOME.key to codexHome.toString(),
                    InstallationEnvironment.ENABLE_LAUNCHD.key to "0",
                    InstallationEnvironment.ENABLE_APP_SERVER.key to "0",
                    InstallationEnvironment.APP_SERVER_TOOLS.key to "query_symbols,source_read",
                    InstallationEnvironment.REFRESH_APP_SERVER.key to "0",
                    InstallationEnvironment.MODE.key to "apply",
                )
            )
        return when (parsed) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("fixture request rejected: ${parsed.failure}")
        }
    }

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
}
