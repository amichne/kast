package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StandaloneServerOptionsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `defaults to workspace local unix domain socket`() {
        val options = StandaloneServerOptions.fromValues(
            mapOf("workspace-root" to tempDir.toString()),
        )

        val transport = options.transport as AnalysisTransport.UnixDomainSocket
        assertEquals(defaultSocketPath(tempDir), transport.socketPath)
    }

    @Test
    fun `parse supports stdio flag`() {
        val options = StandaloneServerOptions.parse(
            arrayOf(
                "--workspace-root=$tempDir",
                "--stdio",
            ),
        )

        assertEquals(AnalysisTransport.Stdio, options.transport)
        assertTrue(options.toCliArguments().contains("--stdio"))
    }

    @Test
    fun `tcp transport survives round trip`() {
        val options = StandaloneServerOptions(
            workspaceRoot = tempDir,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            transport = AnalysisTransport.Tcp(
                host = "127.0.0.1",
                port = 7777,
            ),
            requestTimeoutMillis = 30_000L,
            maxResults = 500,
            maxConcurrentRequests = 4,
        )

        val reparsed = StandaloneServerOptions.parse(options.toCliArguments().toTypedArray())

        val transport = reparsed.transport as AnalysisTransport.Tcp
        assertEquals("127.0.0.1", transport.host)
        assertEquals(7777, transport.port)
    }

    @Test
    fun `explicit socket path survives round trip`() {
        val socketPath = tempDir.resolve("custom").resolve("kast.sock")
        val options = StandaloneServerOptions.fromValues(
            mapOf(
                "workspace-root" to tempDir.toString(),
                "socket-path" to socketPath.toString(),
            ),
        )

        val transport = options.transport as AnalysisTransport.UnixDomainSocket
        assertEquals(socketPath, transport.socketPath)
        assertTrue(options.toCliArguments().contains("--socket-path=$socketPath"))
    }

    @Test
    fun `config supplies default server limits`() {
        val options = StandaloneServerOptions.fromValues(
            values = mapOf("workspace-root" to tempDir.toString()),
            config = KastConfig.defaults().copy(
                server = ServerConfig(
                    maxResults = 42,
                    requestTimeoutMillis = 1234L,
                    maxConcurrentRequests = 7,
                ),
            ),
        )

        assertEquals(42, options.maxResults)
        assertEquals(1234L, options.requestTimeoutMillis)
        assertEquals(7, options.maxConcurrentRequests)
    }
}
