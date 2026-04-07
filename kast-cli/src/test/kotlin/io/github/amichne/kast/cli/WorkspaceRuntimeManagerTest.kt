package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.defaultDescriptorDirectory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceRuntimeManagerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ensureRuntime returns immediately when daemon is INDEXING and requireReady is false`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-indexing").createDirectories()
        val descriptor = writeDescriptor(
            workspaceRoot = workspaceRoot,
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 41),
        )
        val manager = WorkspaceRuntimeManager(
            rpcClient = FakeRuntimeRpcClient(
                runtimeStatuses = mapOf(
                    descriptor.socketPath to listOf(
                        runtimeStatus(
                            workspaceRoot = workspaceRoot,
                            state = RuntimeState.INDEXING,
                            indexing = true,
                        ),
                    ),
                ),
            ),
            processLauncher = FakeProcessLauncher(),
            processLivenessChecker = { pid -> pid == 41L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot), requireReady = false)

        assertFalse(result.started)
        assertEquals(RuntimeState.INDEXING, result.selected.runtimeStatus?.state)
    }

    @Test
    fun `ensureRuntime waits for READY when requireReady is true`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-ready").createDirectories()
        val descriptor = writeDescriptor(
            workspaceRoot = workspaceRoot,
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 52),
        )
        val manager = WorkspaceRuntimeManager(
            rpcClient = FakeRuntimeRpcClient(
                runtimeStatuses = mapOf(
                    descriptor.socketPath to listOf(
                        runtimeStatus(
                            workspaceRoot = workspaceRoot,
                            state = RuntimeState.INDEXING,
                            indexing = true,
                        ),
                        runtimeStatus(
                            workspaceRoot = workspaceRoot,
                            state = RuntimeState.READY,
                            indexing = false,
                        ),
                    ),
                ),
            ),
            processLauncher = FakeProcessLauncher(),
            processLivenessChecker = { pid -> pid == 52L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot), requireReady = true)

        assertFalse(result.started)
        assertTrue(result.selected.ready)
        assertEquals(RuntimeState.READY, result.selected.runtimeStatus?.state)
    }

    @Test
    fun `workspaceEnsure returns during INDEXING when accept-indexing is true`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-ensure-indexing").createDirectories()
        val descriptor = writeDescriptor(
            workspaceRoot = workspaceRoot,
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 61),
        )
        val manager = WorkspaceRuntimeManager(
            rpcClient = FakeRuntimeRpcClient(
                runtimeStatuses = mapOf(
                    descriptor.socketPath to listOf(
                        runtimeStatus(
                            workspaceRoot = workspaceRoot,
                            state = RuntimeState.INDEXING,
                            indexing = true,
                        ),
                    ),
                ),
            ),
            processLauncher = FakeProcessLauncher(),
            processLivenessChecker = { pid -> pid == 61L },
        )

        val result = manager.workspaceEnsure(
            runtimeOptions(workspaceRoot).copy(acceptIndexing = true),
        )

        assertFalse(result.started)
        assertEquals(RuntimeState.INDEXING, result.selected.runtimeStatus?.state)
    }

    @Test
    fun `ensureRuntime auto-start note appears when command starts daemon`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-autostart").createDirectories()
        val launchedDescriptor = descriptor(workspaceRoot = workspaceRoot, pid = 77)
        val manager = WorkspaceRuntimeManager(
            rpcClient = FakeRuntimeRpcClient(
                runtimeStatuses = mapOf(
                    launchedDescriptor.socketPath to listOf(
                        runtimeStatus(
                            workspaceRoot = workspaceRoot,
                            state = RuntimeState.INDEXING,
                            indexing = true,
                        ),
                    ),
                ),
            ),
            processLauncher = FakeProcessLauncher { workingDirectory, _, _ ->
                writeDescriptor(
                    workspaceRoot = workingDirectory,
                    descriptor = launchedDescriptor,
                )
                StartedProcess(
                    pid = launchedDescriptor.pid,
                    logFile = workspaceRoot.resolve(".kast/logs/standalone-daemon.log"),
                )
            },
            processLivenessChecker = { pid -> pid == 77L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot))

        assertTrue(result.started)
        assertTrue(checkNotNull(result.note).contains("state: INDEXING"))
    }

    @Test
    fun `ensureRuntime with no-auto-start fails when no daemon exists`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-no-autostart").createDirectories()
        val manager = WorkspaceRuntimeManager(
            rpcClient = FakeRuntimeRpcClient(runtimeStatuses = emptyMap()),
            processLauncher = FakeProcessLauncher(),
            processLivenessChecker = { false },
        )

        val failure = runCatching {
            manager.ensureRuntime(
                options = runtimeOptions(workspaceRoot).copy(noAutoStart = true),
            )
        }.exceptionOrNull() as? CliFailure

        checkNotNull(failure) {
            "Expected ensureRuntime to fail when no-auto-start is enabled"
        }

        assertEquals("DAEMON_NOT_RUNNING", failure.code)
        assertTrue(failure.message.contains("--no-auto-start"))
    }

    private fun runtimeOptions(workspaceRoot: Path): RuntimeCommandOptions = RuntimeCommandOptions(
        workspaceRoot = workspaceRoot,
        backendName = "standalone",
        waitTimeoutMillis = 2_000L,
    )

    private fun descriptor(
        workspaceRoot: Path,
        pid: Long,
    ): ServerInstanceDescriptor = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot.toString(),
        backendName = "standalone",
        backendVersion = "0.1.0-SNAPSHOT",
        socketPath = workspaceRoot.resolve(".kast/socket").toString(),
        pid = pid,
    )

    private fun runtimeStatus(
        workspaceRoot: Path,
        state: RuntimeState,
        indexing: Boolean,
    ): RuntimeStatusResponse = RuntimeStatusResponse(
        state = state,
        healthy = true,
        active = true,
        indexing = indexing,
        backendName = "standalone",
        backendVersion = "0.1.0-SNAPSHOT",
        workspaceRoot = workspaceRoot.toString(),
        message = state.name,
    )

    private fun writeDescriptor(
        workspaceRoot: Path,
        descriptor: ServerInstanceDescriptor,
    ): ServerInstanceDescriptor {
        val registryDirectory = defaultDescriptorDirectory(workspaceRoot)
        registryDirectory.createDirectories()
        registryDirectory.resolve("${descriptor.pid}.json").writeText(
            defaultCliJson().encodeToString(ServerInstanceDescriptor.serializer(), descriptor),
        )
        return descriptor
    }

    private class FakeRuntimeRpcClient(
        runtimeStatuses: Map<String, List<RuntimeStatusResponse>>,
    ) : RuntimeRpcClient {
        private val statusQueues = runtimeStatuses.mapValues { (_, statuses) -> ArrayDeque(statuses) }

        override fun runtimeStatus(descriptor: ServerInstanceDescriptor): RuntimeStatusResponse {
            val queue = statusQueues[descriptor.socketPath]
                ?: throw CliFailure(code = "DAEMON_UNREACHABLE", message = "No runtime status for ${descriptor.socketPath}")
            if (queue.size > 1) {
                return queue.removeFirst()
            }
            return checkNotNull(queue.firstOrNull())
        }

        override fun capabilities(descriptor: ServerInstanceDescriptor): BackendCapabilities =
            BackendCapabilities(
                backendName = "standalone",
                backendVersion = "0.1.0-SNAPSHOT",
                workspaceRoot = descriptor.workspaceRoot,
                readCapabilities = setOf(ReadCapability.DIAGNOSTICS),
                mutationCapabilities = setOf(MutationCapability.RENAME),
                limits = ServerLimits(
                    maxResults = 500,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
            )
    }

    private class FakeProcessLauncher(
        private val onStart: (Path, Path, List<String>) -> StartedProcess = { _, logFile, _ ->
            StartedProcess(pid = 999, logFile = logFile)
        },
    ) : ProcessLauncher {
        override fun startDetached(
            mainClassName: String,
            workingDirectory: Path,
            logFile: Path,
            arguments: List<String>,
        ): StartedProcess = onStart(workingDirectory, logFile, arguments)
    }
}
