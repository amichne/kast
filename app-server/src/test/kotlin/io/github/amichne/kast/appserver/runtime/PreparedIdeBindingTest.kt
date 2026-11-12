package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.ManagedDeploymentFixture
import io.github.amichne.kast.appserver.ide.cleanupHostedEndpointFixture
import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreparedIdeBindingTest {
    @Test
    fun `changed descriptor is rejected before socket exchange while exact descriptor reaches transport`() {
        val temporary = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kbi-")
        try {
            val fixture = ManagedDeploymentFixture(temporary)
            val home = fixture.home
            Files.writeString(home.resolve("settings.gradle.kts"), "")
            val root = canonicalRootFixture(home)
            val document =
                writeDescriptor(
                    home,
                    IdeHostApplicationAdvertisement(
                        fixture.identity(host = "00000000-0000-0000-0000-000000000001", pid = 123),
                        HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "1.2.3", CanonicalHostedContract.document),
                    ),
                )
            assertTrue(
                ExistingIdeDocuments.descriptor(
                    Files.readAllBytes(Path.of(document.socket).parent.resolve("endpoint.json")),
                    root,
                    Path.of(document.socket),
                ) is Refinement.Refined
            )
            val stale =
                (PreparedWorkspace.admit(
                        root,
                        IdeProjectTarget(
                            "00000000-0000-0000-0000-000000000001",
                            "00000000-0000-0000-0000-000000000003",
                            home.toString(),
                        ),
                    ) as Refinement.Refined)
                    .value
            val fresh =
                (PreparedWorkspace.admit(root, IdeProjectTarget(stale.host.toString(), document.host, home.toString()))
                        as Refinement.Refined)
                    .value
            val client = ExistingIdeSocketClient(home, fixture.configuration)
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
                client.queryPrepared(stale, ExistingIdeOperation.Status),
            )
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE),
                client.queryPrepared(fresh, ExistingIdeOperation.Status),
            )
        } finally {
            cleanupHostedEndpointFixture(temporary.resolve("state/home"))
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun writeDescriptor(home: Path, application: IdeHostApplicationAdvertisement): PreparedEndpointDocument {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(java.util.Locale.ROOT, it)
            }
        val directory =
            Files.createDirectories(
                io.github.amichne.kast.distribution.contract.HostedEndpointPaths.family(
                        home,
                        io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.SEMANTIC,
                    )
                    .resolve(digest)
            )
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val document =
            PreparedEndpointDocument(
                root = home.toString(),
                socket = directory.resolve("host.sock").toString(),
                application = application,
            )
        Files.writeString(
            directory.resolve("endpoint.json"),
            Json { encodeDefaults = true }.encodeToString(serializer<PreparedEndpointDocument>(), document),
        )
        return document
    }
}

@Serializable
private data class PreparedEndpointDocument(
    val root: String,
    val socket: String,
    val application: IdeHostApplicationAdvertisement,
    val type: String = "KAST_IDE_ENDPOINT",
    val protocol: Int = 3,
    val host: String = "00000000-0000-0000-0000-000000000002",
    val hostPid: Long = 123,
    val querySchema: String = "kast.query.run.v10",
    val operations: List<String> =
        listOf(
            "DESCRIBE",
            "CLASS_LOOKUP",
            "DIRECT_SUPERTYPE",
            "QUERY_RUN",
            "SOURCE_READ",
            "DIAGNOSTIC_CHECK",
            "CHANGE_PLAN",
            "CHANGE_APPLY",
            "CHANGE_RECOVER",
        ),
)
