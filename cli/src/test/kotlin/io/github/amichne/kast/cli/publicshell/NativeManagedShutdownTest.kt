package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `kast stop` runs the installed `kast-service disable` in the workspace container's own environment, whose
 * configuration selector names the container profile rather than the installation. No Host runs here.
 */
@Tag("public-launcher")
class NativeManagedShutdownTest {
    @Test
    fun `stop admits the container profile that its private environment selects`(@TempDir evidence: Path) {
        NativePublicFixture(evidence).use { fixture ->
            installService(fixture)
            NativeManagedWorkspaceFixture(
                fixture,
                "#!/bin/sh\nexec \"${fixture.javaExecutable}\" -Duser.home=\"\$HOME\" \"\$@\"\n",
            )
            val installationSelection = fixture.installation.resolve("config/selected-ide.json")
            assertFalse(Files.exists(installationSelection), "only the container profile selects a Host")

            fixture.assertExit(fixture.start("stop", listOf("stop", "--all", "--json")), 0, "stop")

            val document = Json.parseToJsonElement(Files.readString(evidence.resolve("stop.stdout"))).jsonObject
            assertEquals("STOPPED", document.getValue("type").jsonPrimitive.content)
            assertEquals(
                listOf(fixture.workspace.toString()),
                document.getValue("workspaces").jsonArray.map { it.jsonPrimitive.content },
            )
            val progress = Files.readString(evidence.resolve("stop.stderr"))
            assertTrue("\"stage\":\"SHUTDOWN\",\"outcome\":\"COMPLETED\"" in progress, progress)
        }
    }

    /** The staged service entry, admitted by the manifest exactly as the installer records it. */
    private fun installService(fixture: NativePublicFixture) {
        val executable = PosixFilePermissions.fromString("rwxr-xr-x")
        val service = fixture.installation.resolve(SERVICE)
        Files.copy(controlProduct().resolve(SERVICE), service)
        Files.setPosixFilePermissions(service, executable)
        val kast = Files.writeString(fixture.installation.resolve("bin/kast"), "#!/bin/sh\nexit 1\n")
        Files.setPosixFilePermissions(kast, executable)
        val path = fixture.installation.resolve("installation.json")
        val json = Json { encodeDefaults = true }
        val manifest = json.decodeFromString<InstallationManifest>(Files.readString(path))
        val entry = PayloadFile(SERVICE, "sha256:${nativeDigest(service)}", 493)
        Files.writeString(path, json.encodeToString(manifest.copy(payloadFiles = manifest.payloadFiles + entry)))
    }

    private companion object {
        const val SERVICE = "share/kast/libexec/kast-service"
    }
}
