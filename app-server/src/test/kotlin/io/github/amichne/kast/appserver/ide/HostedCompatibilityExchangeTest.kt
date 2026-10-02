package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.appserver.BrokerInstallationState
import io.github.amichne.kast.appserver.InstalledCoordinatorConfiguration
import io.github.amichne.kast.appserver.InstalledWorkspacePreparation
import io.github.amichne.kast.appserver.WorkspaceEnrollmentStore
import io.github.amichne.kast.appserver.ide.HostedSocketExchangeFixture.Descriptor
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Real local transport proves admission/effect ordering; it does not establish native compiler behavior. */
class HostedCompatibilityExchangeTest {
    private val json = Json { encodeDefaults = true }
    private val fixture = HostedSocketExchangeFixture()
    private val compatibility =
        HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "0.49.0", CanonicalHostedContract.document)

    @Test
    fun `absent recorded process with a removed project cannot block live host admission`() {
        fixture.exchange(listOf("DESCRIBE"), compatibility) { _, root ->
            val retired = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
            assertEquals(0, retired.waitFor())
            assertEquals(false, ProcessHandle.of(retired.pid()).map { it.isAlive }.orElse(false))
            val missing = root.path.resolve("removed")
            val directory = Files.createDirectory(root.path.resolve(".kast/ide-hosted/" + "0".repeat(32)))
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            Files.writeString(
                directory.resolve("endpoint.json"),
                json.encodeToString(
                    Descriptor.serializer(),
                    Descriptor(missing.toString(), directory.resolve("host.sock").toString(), hostPid = retired.pid()),
                ),
            )
            val admitted =
                assertInstanceOf(
                    HostedServicesObservation.Admitted::class.java,
                    observeRunningHostedServices(root.path),
                )
            assertEquals(listOf(root), admitted.hosts.map { it.root })
        }
    }

    @Test
    fun `unrelated endpoint family cannot consume eligible project capacity or reach parsing`() {
        fixture.exchange(listOf("DESCRIBE"), compatibility) { _, root ->
            val base = root.path.resolve(".kast/ide-hosted")
            val application = Files.createDirectory(base.resolve("application"))
            // Deliberately malformed bytes prove that the unrelated family never reaches project decoding.
            Files.writeString(application.resolve("endpoint.json"), "not a project descriptor")
            repeat(70) { Files.createDirectory(base.resolve("unrelated-$it")) }
            val admitted =
                assertInstanceOf(
                    HostedServicesObservation.Admitted::class.java,
                    observeRunningHostedServices(root.path),
                )
            assertEquals(listOf(root), admitted.hosts.map { it.root })
        }
    }

    @Test
    fun `live owner with incompatible contract preserves rejection at live admission`() {
        val changed =
            compatibility.copy(
                hostedContract = compatibility.hostedContract.copy(wireSchemaDigest = "sha256:" + "0".repeat(64))
            )
        fixture.exchange(listOf("DESCRIBE"), changed) { _, root ->
            val events = ArrayList<HostedAdmissionEvidence>()
            assertEquals(
                HostedServicesObservation.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED),
                observeRunningHostedServices(root.path, ProcessHostedEndpointOwnerProbe, events::add),
            )
            assertEquals(
                HostedAdmissionEvidence.Rejected(
                    HostedAdmissionStage.LIVE_ADMISSION,
                    ExistingIdeFailure.COMPATIBILITY_REJECTED,
                ),
                events.last(),
            )
        }
    }

    @Test
    fun `registered host status admits live evidence after an independent one shot client query`() {
        fixture.exchange(listOf("DESCRIBE", "DESCRIBE"), compatibility) { oneShot, root ->
            assertInstanceOf(ExistingIdeExchange.Received::class.java, oneShot.query(root, ExistingIdeOperation.Status))
            val preparation = installedPreparation(root.path)
            try {
                val status =
                    assertInstanceOf(HostedServiceStatus.Compatible::class.java, preparation.hostedServices().single())
                assertEquals(root.path.toString(), status.root)
                assertEquals("00000000-0000-0000-0000-000000000002", status.host)
                assertEquals(ProcessHandle.current().pid(), status.hostPid)
                assertEquals("0.49.0", status.hostedPluginVersion)
            } finally {
                runBlocking { preparation.operations.close() }
            }
        }
    }

    @Test
    fun `registered host status preserves actual incompatible contract and host provenance`() {
        val changed =
            compatibility.copy(
                hostedContract = compatibility.hostedContract.copy(wireSchemaDigest = "sha256:" + "0".repeat(64))
            )
        fixture.exchange(listOf("DESCRIBE"), changed) { _, root ->
            val preparation = installedPreparation(root.path)
            try {
                val status =
                    assertInstanceOf(
                        HostedServiceStatus.Incompatible::class.java,
                        preparation.hostedServices().single(),
                    )
                assertEquals(root.path.toString(), status.root)
                assertEquals("0.49.0", status.hostedPluginVersion)
                val mismatch =
                    assertInstanceOf(
                        io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusFailure.Mismatch::class
                            .java,
                        status.failure,
                    )
                assertEquals(
                    io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusField.WIRE_SCHEMA_DIGEST,
                    mismatch.field,
                )
                assertEquals(listOf("sha256:" + "0".repeat(64)), mismatch.observed)
            } finally {
                runBlocking { preparation.operations.close() }
            }
        }
    }

    private fun installedPreparation(home: Path): InstalledWorkspacePreparation {
        val installation = Files.createDirectory(home.resolve("installation"))
        for (name in listOf("bin", "lib", "share")) Files.createDirectory(installation.resolve(name))
        val executable = Files.writeString(installation.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val registry = WorkspaceEnrollmentStore(installation.resolve("config/workspaces.json"))
        assertInstanceOf(Refinement.Refined::class.java, registry.enroll(home))
        val options =
            (InstalledCoordinatorConfiguration.admit(executable, home, emptyMap()) as Refinement.Refined).value
        val owner = (BrokerInstallationState.admit(installation) as Refinement.Refined).value
        return InstalledWorkspacePreparation(options, owner)
    }

    @Test
    fun `different implementation version is retained and operation follows admitted describe`() {
        fixture.exchange(listOf("DESCRIBE", "CLASS_LOOKUP"), compatibility) { client, root ->
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            val observed =
                assertInstanceOf(HostedServiceObservation.Compatible::class.java, client.latestObservation(root))
            assertEquals("0.49.0", observed.compatibility.provenance.version.value)
            assertEquals(CanonicalHostedContract.required, observed.compatibility.hostedContract)
        }
    }

    @Test
    fun `schema contract mismatch rejects before semantic dispatch with exact failure retained`() {
        val changed =
            compatibility.copy(
                hostedContract = compatibility.hostedContract.copy(wireSchemaDigest = "sha256:" + "0".repeat(64))
            )
        fixture.exchange(listOf("DESCRIBE"), changed) { client, root ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED),
                client.query(root, classes()),
            )
            val observed =
                assertInstanceOf(HostedServiceObservation.Incompatible::class.java, client.latestObservation(root))
            assertEquals("0.49.0", observed.provenance.version.value)
            val mismatch =
                assertInstanceOf(
                    io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Mismatch::class.java,
                    observed.compatibilityFailure,
                )
            assertEquals(
                io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
                mismatch.mismatch.field,
            )
        }
    }

    @Test
    fun `socket successor after describe cannot receive an operation under prior admission`() {
        fixture.exchange(listOf("DESCRIBE"), compatibility, replaceSocketBeforeDescribeReply = true) { client, root ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
                client.query(root, classes()),
            )
            assertInstanceOf(HostedServiceObservation.Unavailable::class.java, client.latestObservation(root))
        }
    }

    @Test
    fun `older host missing contract evidence is rejected before semantic dispatch`() {
        fixture.exchange(listOf("DESCRIBE"), compatibility, omitEvidence = true) { client, root ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                client.query(root, classes()),
            )
            assertInstanceOf(HostedServiceObservation.Unavailable::class.java, client.latestObservation(root))
        }
    }

    private fun classes() =
        ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Main") as Refinement.Refined).value)
}
