package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import io.github.amichne.kast.cli.installation.installationManifestJson
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.cli.rpc.InvocationRecordKind
import io.github.amichne.kast.cli.rpc.OneShotInvocationRecord
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledClientContextTest {
    @Test
    fun `unified native payload preserves manifest identity and rejects replacement`(@TempDir temporary: Path) {
        val payload = installedPayload(temporary.toRealPath(), "share/kast/libexec/kast-management")
        val installation = temporary.toRealPath().resolve("path with spaces/installation")
        val context = admitted(payload)
        assertEquals(installation, context.installation)
        assertEquals(
            installation.resolve("config/environment").toString(),
            context.environment["KAST_CONFIGURATION_FILE"],
        )
        assertEquals(InstalledToolAdmission.AVAILABLE, context.observe())
        Files.writeString(payload, "replacement")
        assertEquals(InstalledToolAdmission.UNPROVEN, context.observe())
        assertInstanceOf(Refinement.Rejected::class.java, InstalledClientContext.admit(payload, emptyMap()))
    }

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
        for (privateDirectory in listOf(directory.parent, directory)) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(privateDirectory),
            )
        }
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
    fun `a coordinator-hosted invocation is held by the lease and publishes no one-shot record`(
        @TempDir temporary: Path
    ) {
        val payload = installedPayload(temporary.toRealPath(), "share/kast/libexec/kast-management")
        val context = admitted(payload)
        val installation = context.installation
        Files.writeString(installation.resolve("share/kast/one-shot-observation-v1"), "1\n")
        val witness = checkNotNull(InvocationWitness.CoordinatorLease.begin(context.binding, InvocationRecordKind.RPC))
        assertFalse(Files.exists(installation.resolve("state/run/one-shot")))
        witness.close()
        Files.writeString(installation.parent.resolve(INSTALLATION_SHUTDOWN_FENCE), "fenced")
        assertNull(InvocationWitness.CoordinatorLease.begin(context.binding, InvocationRecordKind.RPC))
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
    fun `replacing the installation manifest retires previously admitted payload authority`(@TempDir temporary: Path) {
        val payload = installedPayload(temporary.toRealPath(), "share/kast/libexec/kast-management")
        val manifest = temporary.resolve("path with spaces/installation/installation.json")
        val context = admitted(payload)
        assertEquals(InstalledToolAdmission.AVAILABLE, context.observe())

        val replacement = manifest.resolveSibling("replacement.json")
        Files.copy(manifest, replacement, StandardCopyOption.COPY_ATTRIBUTES)
        Files.move(replacement, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        assertEquals(InstalledToolAdmission.UNPROVEN, context.observe())
        assertEquals(InstalledToolAdmission.AVAILABLE, admitted(payload).observe())
    }

    @Test
    fun `foreign symlinked and changed payloads cannot grant installation context`(@TempDir temporary: Path) {
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
        Files.delete(payload)
        Files.writeString(payload, "replacement")
        assertEquals(InstalledToolAdmission.UNPROVEN, context.observe())
        assertInstanceOf(Refinement.Rejected::class.java, InstalledClientContext.admit(payload, emptyMap()))
    }

    @Test
    fun `installed semantic entry rejects an unqualified product rather than using a desktop`(
        @TempDir temporary: Path
    ) {
        val payload = installedPayload(temporary.toRealPath(), "bin/kast-query")
        val workspace = Files.createDirectory(temporary.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val session =
            checkNotNull(
                KastDirectToolSession.installed(workspace, admitted(payload).binding, PublicToolCatalog.COMPLETE)
            )
        val identity = PublicToolIdentity.QUERY_SYMBOLS
        val request = PublicToolContract.examples(identity).examples.getValue("runByName").value.jsonObject
        val rejection =
            assertInstanceOf(CliExit.BoundaryRejected::class.java, session.invoke(identity.toolName, request))
        val document = Json.parseToJsonElement(rejection.document.value).jsonObject
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("runtime", document.getValue("boundary").jsonPrimitive.content)
        assertEquals("daemon-operation-unavailable", document.getValue("reason").jsonPrimitive.content)
        assertFalse(Files.exists(payload.parent.parent.parent.resolveSibling("kast-workspaces")))
    }

    @Test
    fun `installed semantic entry preserves the exact coordinator startup rejection`(@TempDir temporary: Path) {
        val cases =
            mapOf<String, (Path) -> Unit>(
                "daemon-operation-startup-ownership-rejected" to
                    { installation ->
                        Files.createDirectories(installation.resolve("state/run"))
                        Files.writeString(installation.resolve("state/run/c.sock"), "foreign")
                    },
                // The digest check rejects the submission before any supervisor or process effect.
                "daemon-operation-startup-submission-kast-qualification-rejected" to
                    { installation ->
                        Files.writeString(installation.resolve("bin/kast-query"), "unqualified bytes")
                    },
            )
        for ((expected, prepare) in cases) {
            val home = Files.createDirectory(temporary.resolve(expected)).toRealPath()
            val payload = installedPayload(home, "bin/kast-query")
            val installation = payload.parent.parent
            prepare(installation)
            val workspace = Files.createDirectory(home.resolve("workspace"))
            Files.writeString(workspace.resolve("settings.gradle.kts"), "")
            val binding =
                when (val located = InstalledClientBinding.locate(payload, mapOf("HOME" to home.toString()))) {
                    is Refinement.Refined -> located.value
                    is Refinement.Rejected -> error("Fixture binding rejected: ${located.failure}")
                }
            val session = checkNotNull(KastDirectToolSession.installed(workspace, binding, PublicToolCatalog.COMPLETE))
            val identity = PublicToolIdentity.QUERY_SYMBOLS
            val request = PublicToolContract.examples(identity).examples.getValue("runByName").value.jsonObject
            val rejection =
                assertInstanceOf(CliExit.BoundaryRejected::class.java, session.invoke(identity.toolName, request))
            val document = Json.parseToJsonElement(rejection.document.value).jsonObject
            assertEquals(setOf("status", "boundary", "reason"), document.keys, expected)
            assertEquals("runtime", document.getValue("boundary").jsonPrimitive.content, expected)
            assertEquals(expected, document.getValue("reason").jsonPrimitive.content)
            assertFalse(Files.exists(installation.resolve("state/broker")), expected)
        }
    }

    @Test
    fun `installed catalogs preserve canonical contracts effects and selected grammar`(@TempDir temporary: Path) {
        val payload = installedPayload(temporary.toRealPath(), "share/kast/libexec/kast-management")
        val context = admitted(payload)
        for (catalog in PublicToolCatalog.entries) {
            val session = checkNotNull(KastDirectToolSession.installed(temporary, context.binding, catalog))
            val actual = session.catalog
            val expected =
                installedHostedBootstrap()
                    .tools
                    .filter { tool -> PublicToolIdentity.entries.any { it.toolName == tool.name } }
                    .map { tool ->
                        val identity = PublicToolIdentity.entries.single { it.toolName == tool.name }
                        tool
                            .directToolDocument()
                            .copy(
                                inputSchema = PublicToolContract.parameters(identity, catalog),
                                description = identity.descriptionFor(catalog),
                            )
                    } + directSupportTools()
            assertEquals(expected, actual, catalog.name)
            assertEquals(
                setOf("query_symbols", "check_diagnostics", "health_check"),
                actual.filter { it.readOnly }.map { it.name }.toSet(),
            )
            assertEquals(
                setOf("add_declaration", "replace_body"),
                actual.filterNot { it.readOnly }.map { it.name }.toSet(),
            )
        }
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
