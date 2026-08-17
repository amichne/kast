package io.github.amichne.kast.indexer

import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstalledIndexerLaunchTest {
    private val runtimeId = "sha256:${"a".repeat(64)}"
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `exact command root and socket refine to installed launch options`() {
        val workspace = Files.createDirectory(temporaryDirectory.resolve("workspace")).toRealPath()
        val socket = temporaryDirectory.resolve("runtime/kast.sock").toAbsolutePath()

        val admission = IndexerLaunchOptions.admit(
            listOf(
                KAST_INDEXER_COMMAND_NAME,
                "--workspace-root=$workspace",
                "--socket-path=$socket",
                "--runtime-id=$runtimeId",
            ),
        )

        val admitted = assertInstanceOf(IndexerLaunchAdmission.Admitted::class.java, admission)
        assertEquals(workspace, admitted.options.workspaceRoot)
        assertEquals(socket, admitted.options.socketPath)
        assertEquals(runtimeId, admitted.options.runtimeId.value)
    }

    @Test
    fun `unknown duplicate or missing arguments fail closed`() {
        val workspace = Files.createDirectory(temporaryDirectory.resolve("workspace")).toRealPath()
        val socket = temporaryDirectory.resolve("runtime/kast.sock").toAbsolutePath()

        val admission = IndexerLaunchOptions.admit(
            listOf(
                KAST_INDEXER_COMMAND_NAME,
                "--workspace-root=$workspace",
                "--workspace-root=$workspace",
                "--socket-path=$socket",
                "--runtime-id=$runtimeId",
                "--compatibility-mode=true",
            ),
        )

        val rejected = assertInstanceOf(IndexerLaunchAdmission.Rejected::class.java, admission)
        assertEquals(
            setOf(
                IndexerLaunchFailure.DUPLICATE_WORKSPACE_ROOT,
                IndexerLaunchFailure.UNKNOWN_ARGUMENT,
            ),
            rejected.failures,
        )
    }

    @Test
    fun `prepared transport owns exact socket state and one canonical exchange`() {
        val workspace = Files.createDirectory(temporaryDirectory.resolve("workspace")).toRealPath()
        val socket = temporaryDirectory.resolve("runtime/kast.sock").toAbsolutePath()
        val options = (IndexerLaunchOptions.admit(
            listOf(
                KAST_INDEXER_COMMAND_NAME,
                "--workspace-root=$workspace",
                "--socket-path=$socket",
                "--runtime-id=$runtimeId",
            ),
        ) as IndexerLaunchAdmission.Admitted).options
        val prepared = assertInstanceOf(
            IndexerTransportPreparation.Prepared::class.java,
            InstalledIndexerTransport.prepare(options),
        )

        prepared.transport.use { transport ->
            assertTrue(Files.isDirectory(transport.stateDirectory))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val served = executor.submit<IndexerConnectionHandling> {
                    transport.serveNext(
                        KastIndexerHost {
                            KastRuntimeDispatch.Responded("response")
                        },
                    )
                }
                val response = SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(socket))
                    assertEquals(IndexerFrameWrite.Written, IndexerWireFrameCodec.write(client, "request"))
                    IndexerWireFrameCodec.read(client)
                }

                assertEquals(IndexerFrameRead.Received("response"), response)
                assertEquals(IndexerConnectionHandling.Served, served.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
        assertTrue(Files.notExists(socket))
    }
}
