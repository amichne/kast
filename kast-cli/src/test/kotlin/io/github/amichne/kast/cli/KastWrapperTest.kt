package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.JsonRpcRequest
import io.github.amichne.kast.api.JsonRpcSuccessResponse
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RefreshResult
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.github.amichne.kast.api.ServerLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

class KastWrapperTest {
    @TempDir
    lateinit var tempDir: Path

    private val transportJson = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `wrapper can ensure status capabilities and diagnostics through the launcher`() {
        val workspace = tempDir.resolve("workspace")
        val sourceFile = workspace
            .resolve("src/main/kotlin/example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )

        try {
            val ensure = runCli(
                "workspace",
                "ensure",
                "--workspace-root=$workspace",
            )
            val ensureResult = defaultCliJson().decodeFromString<WorkspaceEnsureResult>(ensure.stdout)
            assertEquals(workspace.toString(), ensureResult.workspaceRoot)
            assertEquals("uds", ensureResult.selected.descriptor.transport)
            assertTrue(ensure.stderr.contains("daemon:"))

            val status = runCli(
                "workspace",
                "status",
                "--workspace-root=$workspace",
            )
            val statusResult = defaultCliJson().decodeFromString<WorkspaceStatusResult>(status.stdout)
            assertEquals(1, statusResult.candidates.size)
            assertTrue(statusResult.selected?.ready == true)

            val capabilities = runCli(
                "capabilities",
                "--workspace-root=$workspace",
            )
            val capabilitiesResult = defaultCliJson().decodeFromString<BackendCapabilities>(capabilities.stdout)
            assertEquals("standalone", capabilitiesResult.backendName)

            val diagnostics = runCli(
                "diagnostics",
                "--workspace-root=$workspace",
                "--file-paths=$sourceFile",
            )
            val diagnosticsResult = defaultCliJson().decodeFromString<DiagnosticsResult>(diagnostics.stdout)
            assertTrue(diagnosticsResult.diagnostics.isEmpty())
            assertTrue(diagnostics.stderr.contains("daemon:"))
        } finally {
            runCli(
                "daemon",
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
        }
    }

    @Test
    fun `wrapper exposes bash completion script`() {
        val completion = runCli(
            "completion",
            "bash",
        )

        assertTrue(completion.stdout.contains("__kast_complete"))
        assertTrue(completion.stdout.contains("workspace"))
        assertEquals("", completion.stderr)
    }

    @Test
    fun `wrapper propagates custom daemon request timeout through the launcher`() {
        val workspace = tempDir.resolve("workspace-timeout")
        val sourceFile = workspace
            .resolve("src/main/kotlin/example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )

        try {
            val ensure = runCli(
                "workspace",
                "ensure",
                "--workspace-root=$workspace",
                "--request-timeout-ms=120000",
            )
            val ensureResult = defaultCliJson().decodeFromString<WorkspaceEnsureResult>(ensure.stdout)

            assertEquals(120_000L, ensureResult.selected.capabilities?.limits?.requestTimeoutMillis)

            val capabilities = runCli(
                "capabilities",
                "--workspace-root=$workspace",
            )
            val capabilitiesResult = defaultCliJson().decodeFromString<BackendCapabilities>(capabilities.stdout)
            assertEquals(120_000L, capabilitiesResult.limits.requestTimeoutMillis)
        } finally {
            runCli(
                "daemon",
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
        }
    }

    @Test
    fun `workspace refresh updates daemon after external file edits`() {
        val workspace = tempDir.resolve("workspace-refresh")
        val sourceFile = workspace
            .resolve("src/main/kotlin/example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            fun use(): String = greet()
            """.trimIndent() + "\n",
        )

        try {
            runCli(
                "workspace",
                "ensure",
                "--workspace-root=$workspace",
            )

            sourceFile.writeText(
                """
                package example

                fun welcome(): String = "hi"
                fun use(): String = welcome()
                """.trimIndent() + "\n",
            )

            val refresh = runCli(
                "workspace",
                "refresh",
                "--workspace-root=$workspace",
                "--file-paths=$sourceFile",
            )
            val refreshResult = defaultCliJson().decodeFromString<RefreshResult>(refresh.stdout)
            assertEquals(listOf(normalizePath(sourceFile)), refreshResult.refreshedFiles)
            assertTrue(refreshResult.removedFiles.isEmpty())
            assertEquals(false, refreshResult.fullRefresh)

            val rename = runCli(
                "rename",
                "--workspace-root=$workspace",
                "--file-path=$sourceFile",
                "--offset=${sourceFile.readText().indexOf("welcome")}",
                "--new-name=salute",
            )
            val renameResult = defaultCliJson().decodeFromString<RenameResult>(rename.stdout)

            assertTrue(renameResult.edits.isNotEmpty())
            assertTrue(renameResult.edits.all { edit -> edit.newText == "salute" })
        } finally {
            runCli(
                "daemon",
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
        }
    }

    @Test
    fun `daemon automatically refreshes after external file edits`() {
        val workspace = tempDir.resolve("workspace-watch-refresh")
        val sourceFile = workspace
            .resolve("src/main/kotlin/example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            fun use(): String = greet()
            """.trimIndent() + "\n",
        )

        try {
            runCli(
                "workspace",
                "ensure",
                "--workspace-root=$workspace",
            )

            sourceFile.writeText(
                """
                package example

                fun welcome(): String = "hi"
                fun use(): String = welcome()
                """.trimIndent() + "\n",
            )

            waitForCondition("watch-driven refresh for welcome") {
                val rename = runCli(
                    "rename",
                    "--workspace-root=$workspace",
                    "--file-path=$sourceFile",
                    "--offset=${sourceFile.readText().indexOf("welcome")}",
                    "--new-name=salute",
                    allowFailure = true,
                )
                if (rename.exitCode != 0) {
                    return@waitForCondition false
                }

                val renameResult = defaultCliJson().decodeFromString<RenameResult>(rename.stdout)
                renameResult.edits.isNotEmpty() &&
                    renameResult.edits.all { edit ->
                        edit.newText == "salute" &&
                            edit.endOffset - edit.startOffset == "welcome".length
                    }
            }
        } finally {
            runCli(
                "daemon",
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
        }
    }

    @Test
    fun `full workspace refresh handles new and deleted Kotlin files`() {
        val workspace = tempDir.resolve("workspace-structural-refresh")
        val declarationFile = workspace
            .resolve("src/main/kotlin/example/Greeter.kt")
            .createDirectoriesForParent()
        declarationFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )
        val deletedUsageFile = workspace
            .resolve("src/main/kotlin/example/Use.kt")
            .createDirectoriesForParent()
        deletedUsageFile.writeText(
            """
            package example

            fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val normalizedDeletedUsageFile = normalizePath(deletedUsageFile)

        try {
            runCli(
                "workspace",
                "ensure",
                "--workspace-root=$workspace",
            )

            Files.delete(deletedUsageFile)
            val newUsageFile = workspace
                .resolve("src/main/kotlin/example/SecondaryUse.kt")
                .createDirectoriesForParent()
            newUsageFile.writeText(
                """
                package example

                fun useAgain(): String = greet()
                """.trimIndent() + "\n",
            )

            val refresh = runCli(
                "workspace",
                "refresh",
                "--workspace-root=$workspace",
            )
            val refreshResult = defaultCliJson().decodeFromString<RefreshResult>(refresh.stdout)
            assertEquals(true, refreshResult.fullRefresh)
            assertTrue(refreshResult.refreshedFiles.contains(normalizePath(declarationFile)))
            assertTrue(refreshResult.refreshedFiles.contains(normalizePath(newUsageFile)))
            assertTrue(refreshResult.removedFiles.contains(normalizedDeletedUsageFile))

            val references = runCli(
                "references",
                "--workspace-root=$workspace",
                "--file-path=$declarationFile",
                "--offset=${declarationFile.readText().indexOf("greet")}",
                "--include-declaration=false",
            )
            val referencesResult = defaultCliJson().decodeFromString<ReferencesResult>(references.stdout)

            assertEquals(
                listOf(normalizePath(newUsageFile)),
                referencesResult.references.map { reference -> reference.filePath },
            )
        } finally {
            runCli(
                "daemon",
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
        }
    }

    @Test
    fun `wrapper helper parses large rename result from socket daemon`() {
        val workspace = tempDir.resolve("workspace-large-rename")
        val sanitizedWorkspaceRoot = "/workspace/sample-app"
        Files.createDirectories(workspace.resolve(".kast/instances"))
        val socketPath = tempDir.resolve("fake.sock")
        Files.deleteIfExists(socketPath)
        val descriptorPath = workspace.resolve(".kast/instances/fake.json")
        val keepAliveProcess = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()
        descriptorPath.writeText(
            transportJson.encodeToString(
                ServerInstanceDescriptor.serializer(),
                ServerInstanceDescriptor(
                    workspaceRoot = workspace.toString(),
                    backendName = "standalone",
                    backendVersion = "0.1.0",
                    socketPath = socketPath.toString(),
                    pid = keepAliveProcess.pid(),
                ),
            ),
        )

        val runtimeStatus = RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = "standalone",
            backendVersion = "0.1.0",
            workspaceRoot = workspace.toString(),
            message = "ready",
        )
        val capabilities = BackendCapabilities(
            backendName = "standalone",
            backendVersion = "0.1.0",
            workspaceRoot = workspace.toString(),
            readCapabilities = emptySet(),
            mutationCapabilities = setOf(io.github.amichne.kast.api.MutationCapability.RENAME),
            limits = ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 120_000,
                maxConcurrentRequests = 4,
            ),
        )
        val renameResponse = loadRenameResponseFixture()

        val serverThread = startFakeDaemon(
            socketPath = socketPath,
            responsesByMethod = mapOf(
                "runtime/status" to transportJson.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(
                        id = kotlinx.serialization.json.JsonPrimitive(1),
                        result = transportJson.encodeToJsonElement(RuntimeStatusResponse.serializer(), runtimeStatus),
                    ),
                ),
                "capabilities" to transportJson.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(
                        id = kotlinx.serialization.json.JsonPrimitive(1),
                        result = transportJson.encodeToJsonElement(BackendCapabilities.serializer(), capabilities),
                    ),
                ),
                "rename" to renameResponse,
            ),
            expectedRequests = 3,
        )

        try {
            val rename = runCli(
                "rename",
                "--workspace-root=$workspace",
                "--file-path=${workspace.resolve("src/main/kotlin/example/Sample.kt")}",
                "--offset=0",
                "--new-name=RenamedSymbol",
            )

            val renameOutput = defaultCliJson().decodeFromString<RenameResult>(rename.stdout)
            assertEquals(8, renameOutput.edits.size)
            assertTrue(renameOutput.edits.all { edit -> edit.filePath.startsWith(sanitizedWorkspaceRoot) })
            assertTrue(renameOutput.fileHashes.all { fileHash -> fileHash.filePath.startsWith(sanitizedWorkspaceRoot) })
            assertTrue(renameOutput.affectedFiles.all { filePath -> filePath.startsWith(sanitizedWorkspaceRoot) })
            assertTrue(renameOutput.edits.none { edit -> edit.filePath.contains("/Users/") })
            assertTrue(rename.stderr.contains("daemon:"))
        } finally {
            keepAliveProcess.destroyForcibly()
            serverThread.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    private fun runCli(
        vararg args: String,
        allowFailure: Boolean = false,
    ): ProcessResult {
        val wrapper = checkNotNull(System.getProperty("kast.wrapper")) {
            "kast.wrapper system property is missing"
        }
        val process = ProcessBuilder(listOf(wrapper) + args)
            .directory(Path.of("").toAbsolutePath().toFile())
            .start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        check(finished) { "kast wrapper timed out: ${args.joinToString(" ")}" }
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
        if (!allowFailure) {
            assertEquals(0, process.exitValue(), "stderr: $stderr")
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.trim(),
            stderr = stderr.trim(),
        )
    }

    private fun Path.createDirectoriesForParent(): Path {
        Files.createDirectories(checkNotNull(parent))
        return this
    }

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }

    private fun waitForCondition(
        description: String,
        timeoutMillis: Long = 10_000,
        pollMillis: Long = 200,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(pollMillis)
        }
        error("Timed out waiting for $description")
    }

    private fun loadRenameResponseFixture(): String {
        return checkNotNull(javaClass.classLoader.getResourceAsStream("io/github/amichne/kast/cli/large-rename-response.json")) {
            "Missing large rename response fixture"
        }.bufferedReader().use { reader -> reader.readText().trim() }
    }

    private fun startFakeDaemon(
        socketPath: Path,
        responsesByMethod: Map<String, String>,
        expectedRequests: Int,
    ): Thread {
        Files.createDirectories(checkNotNull(socketPath.parent))
        Files.deleteIfExists(socketPath)
        return thread(start = true, isDaemon = true, name = "fake-kast-daemon") {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socketPath))
                repeat(expectedRequests) {
                    server.accept().use { channel ->
                        val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name()).buffered()
                        val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
                        val requestLine = checkNotNull(reader.readLine())
                        val request = transportJson.decodeFromString(JsonRpcRequest.serializer(), requestLine)
                        val response = checkNotNull(responsesByMethod[request.method]) {
                            "Unexpected method: ${request.method}"
                        }
                        writer.write(response)
                        writer.newLine()
                        writer.flush()
                    }
                }
            }
        }
    }
}

private data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
