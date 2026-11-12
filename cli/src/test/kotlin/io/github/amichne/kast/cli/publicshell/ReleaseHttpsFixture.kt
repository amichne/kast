package io.github.amichne.kast.cli.publicshell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

internal data class ReleaseHttpReply(
    val authority: String,
    val path: String,
    val body: String,
    val status: Int = 200,
    val location: String = "http://example.invalid/forbidden",
)

/** An owned CONNECT proxy terminates real TLS for the two production release URLs. */
internal class ReleaseHttpsFixture(
    private val directory: Path,
    private val replies: List<ReleaseHttpReply>,
    private val trusted: Boolean = true,
    private val tunnelStatus: Int = 200,
) : AutoCloseable {
    private val password = "fixture-only".toCharArray()
    private val certificate = directory.resolve("server.p12")
    private val context by lazy(::serverContext)
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = mutableListOf<String>()
    private var tunnels = 0
    private val worker = executor.submit {
        try {
            while (!server.isClosed) server.accept().use(::serve)
        } catch (failure: SocketException) {
            if (!server.isClosed) throw failure
        }
    }

    fun versions(): NativeReleaseResult {
        return runReleaseVersions(
            directory,
            "-Dhttps.proxyHost=${if (tunnelStatus == 200) "localhost" else "127.0.0.1"}",
            "-Dhttps.proxyPort=${server.localPort}",
            *prepareTlsProperties().toTypedArray(),
        )
    }

    private fun prepareTlsProperties(): List<String> {
        if (tunnelStatus != 200) return emptyList()
        context // Provision the server certificate before starting the native client.
        val trustStore = if (trusted) certificate else unrelatedTrustStore()
        return listOf(
            "-Djavax.net.ssl.trustStore=$trustStore",
            "-Djavax.net.ssl.trustStoreType=PKCS12",
            "-Djavax.net.ssl.trustStorePassword=fixture-only",
        )
    }

    override fun close() {
        server.close()
        executor.shutdown()
        try {
            worker.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        assertEquals(replies.size, tunnels, "Unconsumed or excess CONNECT requests")
        assertEquals(if (trusted && tunnelStatus == 200) replies.map { it.path } else emptyList<String>(), requests)
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val reply = replies.getOrNull(tunnels) ?: error("Unexpected CONNECT request")
        assertEquals("CONNECT ${reply.authority}:443 HTTP/1.1", headers(socket.getInputStream()).first())
        tunnels++
        socket.getOutputStream().apply {
            write("HTTP/1.1 $tunnelStatus Fixture\r\nContent-Length: 0\r\n\r\n".toByteArray())
            flush()
        }
        if (tunnelStatus != 200) return
        val tls = context.socketFactory.createSocket(socket, reply.authority, 443, false) as SSLSocket
        try {
            tls.use {
                it.useClientMode = false
                it.startHandshake()
                check(trusted) { "Untrusted certificate was accepted" }
                val request = headers(it.inputStream)
                assertEquals("GET ${reply.path} HTTP/1.1", request.first())
                assertTrue(request.any { line -> line.equals("Host: ${reply.authority}", ignoreCase = true) })
                assertTrue(request.any { line -> line.equals("Cache-Control: no-cache", ignoreCase = true) })
                assertTrue(
                    request.any { line -> line.equals("Accept: application/vnd.github+json", ignoreCase = true) }
                )
                requests.add(reply.path)
                val body = reply.body.toByteArray()
                it.outputStream.apply {
                    write(
                        ("HTTP/1.1 ${reply.status} Fixture\r\nContent-Length: ${body.size}\r\n" +
                                "Location: ${reply.location}\r\nConnection: close\r\n\r\n")
                            .toByteArray()
                    )
                    write(body)
                    flush()
                }
            }
        } catch (failure: IOException) {
            if (trusted) throw failure
        }
    }

    private fun serverContext(): SSLContext {
        generateCertificate(certificate)
        val store =
            KeyStore.getInstance("PKCS12").apply { Files.newInputStream(certificate).use { load(it, password) } }
        val keys =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    private fun generateCertificate(path: Path) {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
        val child =
            ProcessBuilder(
                    keytool.toString(),
                    "-genkeypair",
                    "-alias",
                    "server",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    path.toString(),
                    "-storepass",
                    "fixture-only",
                    "-keypass",
                    "fixture-only",
                    "-dname",
                    "CN=Kast release fixture",
                    "-validity",
                    "1",
                    "-ext",
                    "SAN=dns:api.github.com,dns:raw.githubusercontent.com",
                )
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("keytool.log").toFile())
                .start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Certificate creation did not settle")
            assertEquals(0, child.exitValue(), Files.readString(directory.resolve("keytool.log")))
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun unrelatedTrustStore(): Path = directory.resolve("unrelated.p12").also(::generateCertificate)

    private fun headers(input: InputStream): List<String> {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16_384) {
            val next = input.read()
            check(next >= 0) { "Peer closed before complete headers" }
            bytes.write(next)
            if (bytes.toString(Charsets.US_ASCII).endsWith("\r\n\r\n"))
                return bytes.toString(Charsets.US_ASCII).trimEnd().split("\r\n")
        }
        error("Unexpected oversized fixture request")
    }
}

internal data class NativeReleaseResult(val exit: Int, val output: String, val errors: String)

private fun runReleaseVersions(directory: Path, vararg properties: String): NativeReleaseResult {
    val output = directory.resolve("stdout")
    val errors = directory.resolve("stderr")
    val child =
        ProcessBuilder(
                listOf(System.getProperty("kast.publicLauncher")) +
                    properties +
                    listOf("-Duser.home=$directory", "--internal-entrypoint=management", "versions", "--json")
            )
            .directory(directory.toFile())
            .redirectOutput(output.toFile())
            .redirectError(errors.toFile())
            .apply {
                environment().keys.retainAll(setOf("HOME"))
                environment()["PATH"] = "/usr/bin:/bin"
                environment()["KAST_OUTPUT_TOON"] = "0"
            }
            .start()
    child.outputStream.close()
    try {
        assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Native release read did not settle")
        return NativeReleaseResult(child.exitValue(), Files.readString(output), Files.readString(errors))
    } finally {
        if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
    }
}
