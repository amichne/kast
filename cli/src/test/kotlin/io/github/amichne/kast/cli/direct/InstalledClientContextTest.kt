package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import io.github.amichne.kast.cli.installation.installationManifestJson
import io.github.amichne.kast.cli.rpc.OneShotInvocationRecord
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledClientContextTest {
    @Test
    fun `one admitted native context binds configuration and records only while unfenced`(@TempDir temporary: Path) {
        val payload = installedPayload(temporary.toRealPath(), "bin/kast-query")
        val context = admitted(payload)
        assertEquals(
            payload.parent.parent.resolve("config/environment").toString(),
            context.environment.getValue("KAST_CONFIGURATION_FILE"),
        )
        assertEquals(InstalledToolAdmission.AVAILABLE, context.observe())
        assertNull(OneShotInvocationRecord.begin(context))
        val marker = payload.parent.parent.resolve("share/kast/one-shot-observation-v1")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "1\n")
        val record = checkNotNull(OneShotInvocationRecord.begin(context))
        val directory = payload.parent.parent.resolve("state/run/one-shot")
        assertEquals(1L, Files.list(directory).use { it.count() })
        record.close()
        assertEquals(0L, Files.list(directory).use { it.count() })
        Files.writeString(payload.parent.parent.parent.resolve(INSTALLATION_SHUTDOWN_FENCE), "fenced")
        assertEquals(InstalledToolAdmission.STOPPED, context.observe())
        assertNull(OneShotInvocationRecord.begin(context))
        val stopped = admitted(payload)
        assertEquals(InstalledToolAdmission.STOPPED, stopped.observe())
        assertNull(OneShotInvocationRecord.begin(stopped))
    }

    @Test
    fun `JVM payload and explicit configuration retain their selected identities`(@TempDir temporary: Path) {
        val payload = installedPayload(temporary.toRealPath(), "lib/client.jar")
        val environment = mapOf("KAST_CONFIGURATION_FILE" to temporary.resolve("selected configuration").toString())
        val context = admitted(payload, environment)
        assertEquals(environment, context.environment)
        assertEquals(InstalledToolAdmission.AVAILABLE, context.observe())
    }

    @Test
    fun `foreign detached symlinked and changed payloads cannot grant installation context`(@TempDir temporary: Path) {
        val payload = installedPayload(temporary.toRealPath(), "bin/kast-query")
        val context = admitted(payload)
        val foreign = Files.createDirectories(temporary.resolve("foreign/bin")).resolve("kast-query")
        Files.copy(payload, foreign)
        assertInstanceOf(Refinement.Rejected::class.java, InstalledClientContext.admit(foreign, emptyMap()))
        val alias = temporary.resolve("alias")
        Files.createSymbolicLink(alias, payload.parent.parent)
        assertInstanceOf(
            Refinement.Rejected::class.java,
            InstalledClientContext.admit(alias.resolve("bin/kast-query"), emptyMap()),
        )
        val detached = payload.parent.parent.resolve(".recovery-detached")
        Files.writeString(detached, "detached")
        assertEquals(InstalledToolAdmission.UNPROVEN, context.observe())
        assertInstanceOf(Refinement.Rejected::class.java, InstalledClientContext.admit(payload, emptyMap()))
        Files.delete(detached)
        Files.delete(payload)
        Files.writeString(payload, "replacement")
        assertEquals(InstalledToolAdmission.UNPROVEN, context.observe())
        assertInstanceOf(Refinement.Rejected::class.java, InstalledClientContext.admit(payload, emptyMap()))
    }

    private fun admitted(payload: Path, environment: Map<String, String> = emptyMap()): InstalledClientContext =
        when (val result = InstalledClientContext.admit(payload, environment)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Fixture admission failed: ${result.failure}")
        }

    private fun installedPayload(temporary: Path, relative: String): Path {
        val installation = Files.createDirectories(temporary.resolve("path with spaces/installation"))
        val payload = installation.resolve(relative)
        Files.createDirectories(payload.parent)
        Files.writeString(payload, "qualified fixture bytes")
        val digest =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(payload)).joinToString("") {
                "%02x".format(it)
            }
        Files.writeString(
            installation.resolve("installation.json"),
            installationManifestJson.encodeToString(
                InstallationManifest(
                    semanticVersion = "1.2.3",
                    installationRoot = installation.toString(),
                    payloadIdentity = "sha256:$digest",
                    controlSha256 = "sha256:$digest",
                    codexHome = temporary.toString(),
                    configuration = installation.resolve("config/environment").toString(),
                    workspaceRegistry = installation.resolve("config/workspaces.json").toString(),
                    stateRoot = installation.resolve("state").toString(),
                    externalAnchors = emptyList(),
                    payloadFiles = listOf(PayloadFile(relative, "sha256:$digest", 493)),
                )
            ),
        )
        return payload
    }
}
