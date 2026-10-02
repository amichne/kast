package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.appserver.BrokerInstallationState
import io.github.amichne.kast.appserver.InstalledCoordinatorConfiguration
import io.github.amichne.kast.appserver.InstalledWorkspacePreparation
import io.github.amichne.kast.appserver.WorkspaceEnrollmentStore
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Real local transport proves admission/effect ordering; it does not establish native compiler behavior. */
class HostedCompatibilityExchangeTest {
    private val json = Json { encodeDefaults = true }
    private val compatibility =
        HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "0.49.0", CanonicalHostedContract.document)

    @Test
    fun `three same client operations load required policy once but each admits a fresh describe`() {
        var loads = 0
        exchange(
            script =
                ExchangeScript(
                    expected = List(3) { listOf("DESCRIBE", "CLASS_LOOKUP") }.flatten(),
                    describeMetadata = List(3) { compatibility },
                ),
            clientFactory = { home ->
                ExistingIdeSocketClient(home, ReadLimits.Default, 2_000) {
                    loads += 1
                    check(loads <= 3) { "unexpected required policy load" }
                    requiredHostedCompatibilityPolicy()
                }
            },
        ) { client, root ->
            repeat(3) {
                assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            }
            assertEquals(1, loads)
        }
    }

    @Test
    fun `changed live contract rejects before dispatch after an earlier admitted operation`() {
        val changed =
            compatibility.copy(
                hostedContract = compatibility.hostedContract.copy(wireSchemaDigest = "sha256:" + "0".repeat(64))
            )
        exchange(
            script =
                ExchangeScript(
                    expected = listOf("DESCRIBE", "CLASS_LOOKUP", "DESCRIBE"),
                    describeMetadata = listOf(compatibility, changed),
                )
        ) { client, root ->
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED),
                client.query(root, classes()),
            )
            val observed =
                assertInstanceOf(HostedServiceObservation.Incompatible::class.java, client.latestObservation(root))
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
    fun `rejected required policy load retries successfully on the next request`() {
        var loads = 0
        exchange(
            script =
                ExchangeScript(
                    expected = listOf("DESCRIBE", "DESCRIBE", "CLASS_LOOKUP", "DESCRIBE", "CLASS_LOOKUP"),
                    describeMetadata = List(3) { compatibility },
                ),
            clientFactory = { home ->
                ExistingIdeSocketClient(home, ReadLimits.Default, 2_000) {
                    loads += 1
                    when (loads) {
                        1 -> Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
                        2,
                        3 -> requiredHostedCompatibilityPolicy()
                        else -> error("unexpected required policy load")
                    }
                }
            },
        ) { client, root ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE),
                client.query(root, classes()),
            )
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            assertEquals(2, loads)
        }
    }

    @Test
    fun `absent recorded process with a removed project cannot block live host admission`() {
        exchange(listOf("DESCRIBE"), compatibility) { _, root ->
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
        exchange(listOf("DESCRIBE"), compatibility) { _, root ->
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
        exchange(listOf("DESCRIBE"), changed) { _, root ->
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
        exchange(listOf("DESCRIBE", "DESCRIBE"), compatibility) { oneShot, root ->
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
        exchange(listOf("DESCRIBE"), changed) { _, root ->
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
        exchange(listOf("DESCRIBE", "CLASS_LOOKUP"), compatibility) { client, root ->
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
        exchange(listOf("DESCRIBE"), changed) { client, root ->
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
        exchange(listOf("DESCRIBE"), compatibility, replaceSocketBeforeDescribeReply = true) { client, root ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
                client.query(root, classes()),
            )
            assertInstanceOf(HostedServiceObservation.Unavailable::class.java, client.latestObservation(root))
        }
    }

    @Test
    fun `older host missing contract evidence is rejected before semantic dispatch`() {
        exchange(listOf("DESCRIBE"), compatibility, omitEvidence = true) { client, root ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                client.query(root, classes()),
            )
            assertInstanceOf(HostedServiceObservation.Unavailable::class.java, client.latestObservation(root))
        }
    }

    private fun classes() =
        ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Main") as Refinement.Refined).value)

    private fun exchange(
        expected: List<String>,
        metadata: HostedCompatibilityDocument,
        omitEvidence: Boolean = false,
        replaceSocketBeforeDescribeReply: Boolean = false,
        assert: (ExistingIdeSocketClient, CanonicalRoot) -> Unit,
    ) =
        exchange(
            script =
                ExchangeScript(
                    expected = expected,
                    describeMetadata = expected.filter { it == "DESCRIBE" }.map { metadata },
                    omitEvidence = omitEvidence,
                    replaceSocketBeforeDescribeReply = replaceSocketBeforeDescribeReply,
                ),
            assert = assert,
        )

    private data class ExchangeScript(
        val expected: List<String>,
        val describeMetadata: List<HostedCompatibilityDocument>,
        val omitEvidence: Boolean = false,
        val replaceSocketBeforeDescribeReply: Boolean = false,
    )

    private fun exchange(
        script: ExchangeScript,
        clientFactory: (Path) -> ExistingIdeSocketClient = { ExistingIdeSocketClient(it, exchangeMillis = 2_000) },
        assert: (ExistingIdeSocketClient, CanonicalRoot) -> Unit,
    ) {
        val home = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "khx-")
        val executor = Executors.newSingleThreadExecutor()
        val successors = ArrayList<ServerSocketChannel>()
        try {
            Files.writeString(home.resolve("settings.gradle.kts"), "")
            val root = CanonicalRoot(home)
            val peer =
                peerFixture(
                    home = home,
                    metadata = ArrayDeque(script.describeMetadata),
                    omitEvidence = script.omitEvidence,
                    replaceSocket = script.replaceSocketBeforeDescribeReply,
                    successors = successors,
                )
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(peer.socket))
                val worker =
                    executor.submit<List<String>> {
                        script.expected.map { operation ->
                            server.accept().use { channel -> peer.respond(channel, operation) }
                        }
                    }

                assert(clientFactory(home), root)
                assertEquals(script.expected, worker.get(5, TimeUnit.SECONDS))
                assertEquals(emptyList<HostedCompatibilityDocument>(), peer.metadata.toList())
                successors.forEach { assertNull(it.accept(), "unadmitted successor received a connection") }
            }
        } finally {
            executor.shutdownNow()
            successors.forEach { it.close() }
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun peerFixture(
        home: Path,
        metadata: ArrayDeque<HostedCompatibilityDocument>,
        omitEvidence: Boolean,
        replaceSocket: Boolean,
        successors: MutableList<ServerSocketChannel>,
    ): PeerFixture {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(it)
            }
        val directory = Files.createDirectories(home.resolve(".kast/ide-hosted/$digest"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val socket = directory.resolve("host.sock")
        val descriptor = Descriptor(home.toString(), socket.toString())
        Files.writeString(directory.resolve("endpoint.json"), json.encodeToString(Descriptor.serializer(), descriptor))
        return PeerFixture(
            metadata = metadata,
            omitEvidence = omitEvidence,
            replaceSocket = replaceSocket,
            socket = socket,
            descriptor = descriptor,
            successors = successors,
        )
    }

    private inner class PeerFixture(
        val metadata: ArrayDeque<HostedCompatibilityDocument>,
        val omitEvidence: Boolean,
        val replaceSocket: Boolean,
        val socket: Path,
        val descriptor: Descriptor,
        val successors: MutableList<ServerSocketChannel>,
    ) {
        fun respond(channel: SocketChannel, operation: String): String {
            val input = DataInputStream(Channels.newInputStream(channel))
            val request = Json.parseToJsonElement(input.readNBytes(input.readInt()).decodeToString()).jsonObject
            val received = request.getValue("type").jsonPrimitive.content
            assertEquals(operation, received, "unexpected fixture request")
            val bytes = document(operation).toByteArray()
            if (replaceSocket) {
                Files.move(socket, socket.parent.resolve("retired.sock"))
                val successor = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                successors += successor
                successor.bind(UnixDomainSocketAddress.of(socket))
                successor.configureBlocking(false)
            }
            DataOutputStream(Channels.newOutputStream(channel)).apply {
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
            return received
        }

        private fun document(operation: String): String {
            if (operation != "DESCRIBE") return json.encodeToString(Rejection.serializer(), Rejection())
            val description = metadata.removeFirst()
            return if (omitEvidence) {
                json.encodeToString(
                    LegacyHost.serializer(),
                    LegacyHost(descriptor.root, descriptor.host, descriptor.hostPid),
                )
            } else {
                json.encodeToString(
                    Host.serializer(),
                    Host(
                        root = descriptor.root,
                        host = descriptor.host,
                        hostPid = descriptor.hostPid,
                        compatibility = description,
                    ),
                )
            }
        }
    }

    @Serializable
    private data class Descriptor(
        val root: String,
        val socket: String,
        val type: String = "KAST_IDE_ENDPOINT",
        val protocol: Int = 3,
        val hostPid: Long = ProcessHandle.current().pid(),
        val host: String = "00000000-0000-0000-0000-000000000002",
        val querySchema: String = "kast.query.run.v3",
        val operations: List<String> = operations(),
    )

    @Serializable
    private data class Host(
        val root: String,
        val host: String,
        val hostPid: Long,
        val compatibility: HostedCompatibilityDocument,
        val type: String = "KAST_IDE_HOST",
        val protocol: Int = 3,
        val querySchema: String = "kast.query.run.v3",
        val indexAuthority: String = "existing_ide_kotlin_stub_index",
        val operations: List<String> = operations(),
    )

    @Serializable
    private data class Rejection(val type: String = "HOST_REJECTED", val failure: String = "INVALID_REQUEST")

    @Serializable
    private data class LegacyHost(
        val root: String,
        val host: String,
        val hostPid: Long,
        val type: String = "KAST_IDE_HOST",
        val protocol: Int = 3,
        val querySchema: String = "kast.query.run.v3",
        val indexAuthority: String = "existing_ide_kotlin_stub_index",
        val operations: List<String> = operations(),
    )

    companion object {
        private fun operations() =
            listOf(
                "DESCRIBE",
                "CLASS_LOOKUP",
                "DIRECT_SUPERTYPE",
                "QUERY_RUN",
                "SOURCE_READ",
                "DIAGNOSTIC_CHECK",
                "CHANGE_PLAN",
                "CHANGE_APPROVAL_PREPARE",
                "CHANGE_APPLY",
                "CHANGE_RECOVER",
            )
    }
}
