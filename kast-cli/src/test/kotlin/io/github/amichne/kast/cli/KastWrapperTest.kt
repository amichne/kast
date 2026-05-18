package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.cli.results.WorkspaceEnsureResult
import io.github.amichne.kast.cli.results.WorkspaceStatusResult
import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KastWrapperTest {
    @TempDir
    lateinit var tempDir: Path

    private val transportJson = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `test workspaces are isolated from parent Gradle discovery`() {
        val workspace = createIsolatedTestWorkspace("workspace-isolation-test")

        assertEquals(
            "rootProject.name = \"workspace-isolation-test\"\n",
            workspace.resolve("settings.gradle.kts").readText(),
        )
        assertEquals(workspace.resolve("src/main/kotlin"), standaloneSourceRoot(workspace))
    }

    @Test
    fun `wrapper can ensure status capabilities and diagnostics through the launcher`() {
        val workspace = createIsolatedTestWorkspace("workspace")
        val sourceFile = standaloneSourceRoot(workspace)
            .resolve("example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )

        val daemon = startIsolatedRealBackend(workspace)
        try {
            val ensure = runCli(
                "up",
                "--workspace-root=$workspace",
            )
            val ensureResult = defaultCliJson().decodeFromString<WorkspaceEnsureResult>(ensure.stdout)
            assertEquals(workspace.toString(), ensureResult.workspaceRoot)
            assertEquals("uds", ensureResult.selected.descriptor.transport)
            assertTrue(ensure.stderr.contains("daemon:"))

            val status = runCli(
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
                "rpc",
                rpcRequest(
                    method = "raw/diagnostics",
                    params = transportJson.encodeToJsonElement(
                        DiagnosticsQuery.serializer(),
                        DiagnosticsQuery(filePaths = listOf(sourceFile.toString())),
                    ),
                ),
                "--workspace-root=$workspace",
            )
            val diagnosticsResult = decodeRpcResult(diagnostics.stdout, DiagnosticsResult.serializer())
            assertTrue(diagnosticsResult.diagnostics.isEmpty())
            assertTrue(diagnostics.stderr.isBlank())
        } finally {
            runCli(
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon.destroyForcibly()
        }
    }

    @Test
    fun `workspace-symbol wrapper searches symbols through launcher`() {
        val workspace = createIsolatedTestWorkspace("workspace-symbol-wrapper")
        val sourceFile = standaloneSourceRoot(workspace)
            .resolve("example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = \"hi\"
            """.trimIndent() + "\n",
        )

        val daemon = startIsolatedRealBackend(workspace)
        try {
            runCli(
                "up",
                "--workspace-root=$workspace",
            )

            val search = runCli(
                "rpc",
                rpcRequest(
                    method = "raw/workspace-symbol",
                    params = transportJson.encodeToJsonElement(
                        WorkspaceSymbolQuery.serializer(),
                        WorkspaceSymbolQuery(pattern = "greet"),
                    ),
                ),
                "--workspace-root=$workspace",
            )
            val success = decodeRpcResult(search.stdout, WorkspaceSymbolResult.serializer())

            assertTrue(success.symbols.any { symbol -> symbol.fqName == "example.greet" })
        } finally {
            runCli(
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon.destroyForcibly()
        }
    }

    @Test
    fun `file-outline wrapper returns nested declarations through launcher`() {
        val workspace = createIsolatedTestWorkspace("file-outline-wrapper")
        val sourceFile = standaloneSourceRoot(workspace)
            .resolve("example/Greeter.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            class Greeter {
                fun greet(): String = \"hi\"
            }
            """.trimIndent() + "\n",
        )

        val daemon = startIsolatedRealBackend(workspace)
        try {
            runCli(
                "up",
                "--workspace-root=$workspace",
            )

            val outline = runCli(
                "rpc",
                rpcRequest(
                    method = "raw/file-outline",
                    params = transportJson.encodeToJsonElement(
                        FileOutlineQuery.serializer(),
                        FileOutlineQuery(filePath = sourceFile.toString()),
                    ),
                ),
                "--workspace-root=$workspace",
            )
            val success = decodeRpcResult(outline.stdout, FileOutlineResult.serializer())

            assertTrue(success.symbols.any { symbol -> symbol.symbol.fqName == "example.Greeter" })
            val greeter = success.symbols.first { symbol -> symbol.symbol.fqName == "example.Greeter" }
            assertTrue(greeter.children.any { child -> child.symbol.fqName == "example.Greeter.greet" })
        } finally {
            runCli(
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon.destroyForcibly()
        }
    }

    @Test
    fun `wrapper exposes bash completion script`() {
        val completion = runCli(
            "completion",
            "bash",
        )

        assertTrue(completion.stdout.contains("__kast_complete"))
        assertTrue(completion.stdout.contains("--workspace-root"))
        assertTrue(!completion.stdout.contains("workspace ensure"))
        assertTrue(!completion.stdout.contains("workspace status"))
        assertTrue(!completion.stdout.contains("workspace stop"))
        assertEquals("", completion.stderr)
    }

    @Test
    fun `wrapper propagates custom daemon request timeout through the launcher`() {
        val workspace = createIsolatedTestWorkspace("workspace-timeout")
        val sourceFile = standaloneSourceRoot(workspace)
            .resolve("example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )

        val daemon = startIsolatedRealBackend(workspace, extraArgs = listOf("--request-timeout-ms=120000"))
        try {
            val ensure = runCli(
                "up",
                "--workspace-root=$workspace",
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
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon.destroyForcibly()
        }
    }

    @Test
    fun `workspace refresh updates daemon after external file edits`() {
        val workspace = createIsolatedTestWorkspace("workspace-refresh")
        val sourceFile = standaloneSourceRoot(workspace)
            .resolve("example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            fun use(): String = greet()
            """.trimIndent() + "\n",
        )

        var daemon: Process? = null
        try {
            daemon = startIsolatedRealBackend(workspace)

            sourceFile.writeText(
                """
                package example

                fun welcome(): String = "hi"
                fun use(): String = welcome()
                """.trimIndent() + "\n",
            )

            val refresh = runCli(
                "rpc",
                rpcRequest(
                    method = "raw/workspace-refresh",
                    params = transportJson.encodeToJsonElement(
                        RefreshQuery.serializer(),
                        RefreshQuery(filePaths = listOf(sourceFile.toString())),
                    ),
                ),
                "--workspace-root=$workspace",
            )
            val refreshResult = decodeRpcResult(refresh.stdout, RefreshResult.serializer())
            assertEquals(listOf(normalizePath(sourceFile)), refreshResult.refreshedFiles)
            assertTrue(refreshResult.removedFiles.isEmpty())
            assertEquals(false, refreshResult.fullRefresh)

            // The K2 analysis session rebuild after refresh may not complete
            // synchronously on slow CI runners, so poll until rename succeeds.
            waitForCondition("rename after refresh resolves welcome", timeoutMillis = 120_000) {
                val rename = runCli(
                    "rpc",
                    rpcRequest(
                        method = "raw/rename",
                        params = transportJson.encodeToJsonElement(
                            RenameQuery.serializer(),
                            RenameQuery(
                                position = FilePosition(
                                    filePath = sourceFile.toString(),
                                    offset = sourceFile.readText().indexOf("welcome"),
                                ),
                                newName = "salute",
                                dryRun = true,
                            ),
                        ),
                    ),
                    "--workspace-root=$workspace",
                    allowFailure = true,
                )
                if (rename.exitCode != 0) {
                    return@waitForCondition false
                }
                val renameResult = decodeRpcResult(rename.stdout, RenameResult.serializer())
                renameResult.edits.isNotEmpty() &&
                    renameResult.edits.all { edit -> edit.newText == "salute" }
            }
        } finally {
            runCli(
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon?.destroyForcibly()
        }
    }

    @Test
    fun `daemon automatically refreshes after external file edits`() {
        val workspace = createIsolatedTestWorkspace("workspace-watch-refresh")
        val sourceFile = standaloneSourceRoot(workspace)
            .resolve("example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            fun use(): String = greet()
            """.trimIndent() + "\n",
        )

        var daemon: Process? = null
        try {
            daemon = startIsolatedRealBackend(workspace)

            sourceFile.writeText(
                """
                package example

                fun welcome(): String = "hi"
                fun use(): String = welcome()
                """.trimIndent() + "\n",
            )

            waitForCondition("watch-driven refresh for welcome", timeoutMillis = 120_000) {
                val rename = runCli(
                    "rpc",
                    rpcRequest(
                        method = "raw/rename",
                        params = transportJson.encodeToJsonElement(
                            RenameQuery.serializer(),
                            RenameQuery(
                                position = FilePosition(
                                    filePath = sourceFile.toString(),
                                    offset = sourceFile.readText().indexOf("welcome"),
                                ),
                                newName = "salute",
                                dryRun = true,
                            ),
                        ),
                    ),
                    "--workspace-root=$workspace",
                    allowFailure = true,
                )
                if (rename.exitCode != 0) {
                    return@waitForCondition false
                }

                val renameResult = decodeRpcResult(rename.stdout, RenameResult.serializer())
                renameResult.edits.isNotEmpty() &&
                    renameResult.edits.all { edit ->
                        edit.newText == "salute" &&
                            edit.endOffset - edit.startOffset == "welcome".length
                    }
            }
        } finally {
            runCli(
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon?.destroyForcibly()
        }
    }

    @Test
    fun `full workspace refresh handles new and deleted Kotlin files`() {
        val workspace = createIsolatedTestWorkspace("workspace-structural-refresh")
        val declarationFile = standaloneSourceRoot(workspace)
            .resolve("example/Greeter.kt")
            .createDirectoriesForParent()
        declarationFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )
        val deletedUsageFile = standaloneSourceRoot(workspace)
            .resolve("example/Use.kt")
            .createDirectoriesForParent()
        deletedUsageFile.writeText(
            """
            package example

            fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val normalizedDeletedUsageFile = normalizePath(deletedUsageFile)

        var daemon: Process? = null
        try {
            daemon = startIsolatedRealBackend(workspace)

            Files.delete(deletedUsageFile)
            val newUsageFile = standaloneSourceRoot(workspace)
                .resolve("example/SecondaryUse.kt")
                .createDirectoriesForParent()
            newUsageFile.writeText(
                """
                package example

                fun useAgain(): String = greet()
                """.trimIndent() + "\n",
            )

            val refresh = runCli(
                "rpc",
                rpcRequest(
                    method = "raw/workspace-refresh",
                    params = transportJson.encodeToJsonElement(
                        RefreshQuery.serializer(),
                        RefreshQuery(),
                    ),
                ),
                "--workspace-root=$workspace",
            )
            val refreshResult = decodeRpcResult(refresh.stdout, RefreshResult.serializer())
            assertEquals(true, refreshResult.fullRefresh)
            assertTrue(refreshResult.refreshedFiles.contains(normalizePath(declarationFile)))
            assertTrue(refreshResult.refreshedFiles.contains(normalizePath(newUsageFile)))
            assertTrue(refreshResult.removedFiles.contains(normalizedDeletedUsageFile))

            val references = runCli(
                "rpc",
                rpcRequest(
                    method = "raw/references",
                    params = transportJson.encodeToJsonElement(
                        ReferencesQuery.serializer(),
                        ReferencesQuery(
                            position = FilePosition(
                                filePath = declarationFile.toString(),
                                offset = declarationFile.readText().indexOf("greet"),
                            ),
                            includeDeclaration = false,
                        ),
                    ),
                ),
                "--workspace-root=$workspace",
            )
            val referencesResult = decodeRpcResult(references.stdout, ReferencesResult.serializer())

            assertEquals(
                listOf(normalizePath(newUsageFile)),
                referencesResult.references.map { reference -> reference.filePath },
            )
        } finally {
            runCli(
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
            daemon?.destroyForcibly()
        }
    }

    @Test
    fun `wrapper helper parses large rename result from socket daemon`() {
        val workspace = tempDir.resolve("workspace-large-rename")
        val sanitizedWorkspaceRoot = "/workspace/sample-app"
        val configHome = tempDir.resolve("config-home")
        val home = tempDir.resolve("home")
        val socketPath = tempDir.resolve("fake.sock")
        Files.deleteIfExists(socketPath)
        val keepAliveProcess = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = workspace.toString(),
            backendName = "standalone",
            backendVersion = "0.1.0",
            socketPath = socketPath.toString(),
            pid = keepAliveProcess.pid(),
        )
        val daemonsDir = configHome.resolve("daemons")
        Files.createDirectories(daemonsDir)
        Files.createDirectories(configHome)
        configHome.resolve("config.toml").writeText(
            """
            [paths]
            descriptorDir = "$daemonsDir"
            """.trimIndent(),
        )
        io.github.amichne.kast.api.client.DescriptorRegistry(daemonsDir.resolve("daemons.json")).register(descriptor)
        val defaultDaemonsDir = home.resolve(".kast/cache/daemons")
        Files.createDirectories(defaultDaemonsDir)
        io.github.amichne.kast.api.client.DescriptorRegistry(defaultDaemonsDir.resolve("daemons.json")).register(descriptor)

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
            mutationCapabilities = setOf(io.github.amichne.kast.api.contract.MutationCapability.RENAME),
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
                "raw/rename" to renameResponse,
            ),
            expectedRequests = 3,
        )

        try {
            val rename = runCli(
                "rpc",
                rpcRequest(
                    method = "raw/rename",
                    params = transportJson.encodeToJsonElement(
                        RenameQuery.serializer(),
                        RenameQuery(
                            position = FilePosition(
                                filePath = workspace.resolve("src/main/kotlin/example/Sample.kt").toString(),
                                offset = 0,
                            ),
                            newName = "RenamedSymbol",
                            dryRun = true,
                        ),
                    ),
                ),
                "--workspace-root=$workspace",
                env = mapOf("KAST_CONFIG_HOME" to configHome.toString(), "JAVA_OPTS" to "-Duser.home=$home"),
            )

            val renameOutput = decodeRpcResult(rename.stdout, RenameResult.serializer())
            assertEquals(8, renameOutput.edits.size)
            assertTrue(renameOutput.edits.all { edit -> edit.filePath.startsWith(sanitizedWorkspaceRoot) })
            assertTrue(renameOutput.fileHashes.all { fileHash -> fileHash.filePath.startsWith(sanitizedWorkspaceRoot) })
            assertTrue(renameOutput.affectedFiles.all { filePath -> filePath.startsWith(sanitizedWorkspaceRoot) })
            assertTrue(renameOutput.edits.none { edit -> edit.filePath.contains("/Users/") })
            assertTrue(rename.stderr.isBlank())
        } finally {
            keepAliveProcess.destroyForcibly()
            serverThread.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    private fun rpcRequest(method: String, params: JsonElement? = null): String =
        transportJson.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(method = method, params = params),
        )

    private fun <T> decodeRpcResult(stdout: String, serializer: KSerializer<T>): T {
        val envelope = defaultCliJson().decodeFromString(JsonRpcSuccessResponse.serializer(), stdout)
        return defaultCliJson().decodeFromJsonElement(serializer, envelope.result)
    }

    private fun runCli(
        vararg args: String,
        allowFailure: Boolean = false,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val wrapper = checkNotNull(System.getProperty("kast.wrapper")) {
            "kast.wrapper system property is missing"
        }
        val process = ProcessBuilder(listOf(wrapper) + args)
            .directory(Path.of("").toAbsolutePath().toFile())
            .also { pb -> env.forEach { (k, v) -> pb.environment()[k] = v } }
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

    private fun createIsolatedTestWorkspace(name: String): Path {
        val workspace = tempDir.resolve(name)
        Files.createDirectories(workspace)
        workspace.resolve("settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")
        return workspace
    }

    private fun standaloneSourceRoot(workspace: Path): Path = workspace.resolve("src/main/kotlin")

    private fun startIsolatedRealBackend(
        workspace: Path,
        extraArgs: List<String> = emptyList(),
        timeoutMillis: Long = 120_000,
    ): Process {
        return startRealBackend(
            workspace = workspace,
            extraArgs = listOf("--source-roots=${standaloneSourceRoot(workspace)}") + extraArgs,
            timeoutMillis = timeoutMillis,
        )
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

    private fun startRealBackend(
        workspace: Path,
        extraArgs: List<String> = emptyList(),
        timeoutMillis: Long = 120_000,
    ): Process {
        return startStandaloneBackendForTest(
            workspace = workspace,
            extraArgs = extraArgs,
            timeoutMillis = timeoutMillis,
            statusProbe = {
                val result = runCli("status", "--workspace-root=$workspace", allowFailure = true)
                BackendStatusProbeSnapshot(
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                )
            },
            isReady = { probe ->
                probe.exitCode == 0 &&
                    runCatching {
                        defaultCliJson().decodeFromString<WorkspaceStatusResult>(probe.stdout)
                    }.getOrNull()?.selected?.ready == true
            },
        )
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
