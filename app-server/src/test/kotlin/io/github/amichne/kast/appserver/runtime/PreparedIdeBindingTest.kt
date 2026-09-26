package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreparedIdeBindingTest {
    @Test
    fun `changed descriptor is rejected before socket exchange while exact descriptor reaches transport`() {
        val home = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kbi-")
        try {
            Files.writeString(home.resolve("settings.gradle.kts"), "")
            val root = CanonicalRoot(home)
            val document = writeDescriptor(home)
            val encoded = Json { encodeDefaults = true }.encodeToString(PreparedEndpointDocument.serializer(), document)
            assertTrue(
                ExistingIdeDocuments.descriptor(encoded.toByteArray(), root, Path.of(document.socket))
                    is Refinement.Refined
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
            val client = ExistingIdeSocketClient(home)
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
                client.queryPrepared(stale, ExistingIdeOperation.Status),
            )
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE),
                client.queryPrepared(fresh, ExistingIdeOperation.Status),
            )
        } finally {
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun writeDescriptor(home: Path): PreparedEndpointDocument {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(it)
            }
        val directory = Files.createDirectories(home.resolve(".kast/ide-hosted/$digest"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val document =
            PreparedEndpointDocument(root = home.toString(), socket = directory.resolve("host.sock").toString())
        Files.writeString(
            directory.resolve("endpoint.json"),
            Json { encodeDefaults = true }.encodeToString(PreparedEndpointDocument.serializer(), document),
        )
        return document
    }
}

@Serializable
private data class PreparedEndpointDocument(
    val root: String,
    val socket: String,
    val type: String = "KAST_IDE_ENDPOINT",
    val protocol: Int = 3,
    val host: String = "00000000-0000-0000-0000-000000000002",
    val hostPid: Long = 123,
    val querySchema: String = "kast.query.run.v2",
    val operations: List<String> =
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
        ),
)
