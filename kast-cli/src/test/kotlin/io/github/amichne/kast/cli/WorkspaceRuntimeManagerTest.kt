package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.DescriptorRegistry
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkspaceRuntimeManagerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var configHome: Path
    private lateinit var envLookup: (String) -> String?

    @BeforeEach
    fun setUp() {
        configHome = tempDir.resolve("config-home")
        envLookup = mapOf("KAST_CONFIG_HOME" to configHome.toString())::get
    }

    @Test
    fun `ensureRuntime returns immediately when daemon is INDEXING and requireReady is false`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-indexing")
        val descriptor = writeDescriptor(
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 41),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(
                    runtimeStatus(workspaceRoot = workspaceRoot, state = RuntimeState.INDEXING, indexing = true),
                ),
            ),
            processLivenessChecker = { pid -> pid == 41L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot), requireReady = false)

        assertFalse(result.started)
        assertEquals(RuntimeState.INDEXING, result.selected.runtimeStatus?.state)
    }

    @Test
    fun `ensureRuntime waits for READY when requireReady is true`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-ready")
        val descriptor = writeDescriptor(
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 52),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(
                    runtimeStatus(workspaceRoot = workspaceRoot, state = RuntimeState.INDEXING, indexing = true),
                    runtimeStatus(workspaceRoot = workspaceRoot, state = RuntimeState.READY, indexing = false),
                ),
            ),
            processLivenessChecker = { pid -> pid == 52L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot), requireReady = true)

        assertFalse(result.started)
        assertTrue(result.selected.ready)
        assertEquals(RuntimeState.READY, result.selected.runtimeStatus?.state)
    }

    @Test
    fun `workspaceEnsure returns during INDEXING when accept-indexing is true`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-ensure-indexing")
        val descriptor = writeDescriptor(
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 61),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(
                    runtimeStatus(workspaceRoot = workspaceRoot, state = RuntimeState.INDEXING, indexing = true),
                ),
            ),
            processLivenessChecker = { pid -> pid == 61L },
        )

        val result = manager.workspaceEnsure(runtimeOptions(workspaceRoot).copy(acceptIndexing = true))

        assertFalse(result.started)
        assertEquals(RuntimeState.INDEXING, result.selected.runtimeStatus?.state)
    }

    @Test
    fun `ensureRuntime auto-start note appears when command starts daemon`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-autostart")
        val launchedDescriptor = descriptor(workspaceRoot = workspaceRoot, pid = 77)
        val manager = managerWith(
            runtimeStatuses = mapOf(
                launchedDescriptor.socketPath to listOf(
                    runtimeStatus(workspaceRoot = workspaceRoot, state = RuntimeState.INDEXING, indexing = true),
                ),
            ),
            processLauncher = FakeProcessLauncher { _, logFile, _ ->
                writeDescriptor(descriptor = launchedDescriptor)
                StartedProcess(pid = launchedDescriptor.pid, logFile = logFile)
            },
            processLivenessChecker = { pid -> pid == 77L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot))

        assertTrue(result.started)
        assertTrue(checkNotNull(result.note).contains("state: INDEXING"))
    }

    @Test
    fun `ensureRuntime with no-auto-start fails when no daemon exists`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-no-autostart")
        val manager = managerWith(
            runtimeStatuses = emptyMap(),
            processLivenessChecker = { false },
        )

        val failure = runCatching {
            manager.ensureRuntime(options = runtimeOptions(workspaceRoot).copy(noAutoStart = true))
        }.exceptionOrNull() as? CliFailure

        checkNotNull(failure) { "Expected ensureRuntime to fail when no-auto-start is enabled" }
        assertEquals("DAEMON_NOT_RUNNING", failure.code)
        assertTrue(failure.message.contains("--no-auto-start"))
    }

    @Test
    fun `workspaceStop stops running backend and removes descriptor`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-stop")
        val descriptor = writeDescriptor(
            descriptor = descriptor(workspaceRoot = workspaceRoot, pid = 88),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(
                    runtimeStatus(workspaceRoot = workspaceRoot, state = RuntimeState.READY, indexing = false),
                ),
            ),
            processLivenessChecker = { pid -> pid == 88L },
        )

        val result = manager.workspaceStop(runtimeOptions(workspaceRoot))
        assertTrue(result.stopped)
        assertEquals(88L, result.pid)
        assertEquals(workspaceRoot.toString(), result.workspaceRoot)
    }

    @Test
    fun `inspectWorkspace discovers intellij backend descriptor`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-intellij")
        val desc = writeDescriptor(
            descriptor = descriptor(workspaceRoot, pid = 100, backendName = "intellij"),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                desc.socketPath to listOf(
                    runtimeStatus(workspaceRoot, RuntimeState.READY, indexing = false, backendName = "intellij"),
                ),
            ),
            processLivenessChecker = { pid -> pid == 100L },
        )

        val result = manager.ensureRuntime(
            options = runtimeOptions(workspaceRoot, backendName = "intellij"),
            requireReady = false,
        )

        assertFalse(result.started)
        assertEquals("intellij", result.selected.descriptor.backendName)
    }

    @Test
    fun `ensureRuntime prefers intellij when both backends available and no explicit backend`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-both")
        val standaloneDesc = writeDescriptor(
            descriptor = descriptor(workspaceRoot, pid = 200, backendName = "standalone"),
        )
        val intellijDesc = writeDescriptor(
            descriptor = descriptor(workspaceRoot, pid = 201, backendName = "intellij"),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                standaloneDesc.socketPath to listOf(
                    runtimeStatus(workspaceRoot, RuntimeState.READY, indexing = false, backendName = "standalone"),
                ),
                intellijDesc.socketPath to listOf(
                    runtimeStatus(workspaceRoot, RuntimeState.READY, indexing = false, backendName = "intellij"),
                ),
            ),
            processLivenessChecker = { pid -> pid in setOf(200L, 201L) },
        )

        val result = manager.ensureRuntime(
            options = runtimeOptions(workspaceRoot, backendName = null),
            requireReady = false,
        )

        assertFalse(result.started)
        assertEquals("intellij", result.selected.descriptor.backendName)
    }

    @Test
    fun `ensureRuntime fails with clear message when intellij backend not available`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-no-intellij")
        val manager = managerWith(
            runtimeStatuses = emptyMap(),
            processLivenessChecker = { false },
        )

        val failure = runCatching {
            manager.ensureRuntime(options = runtimeOptions(workspaceRoot, backendName = "intellij"))
        }.exceptionOrNull() as? CliFailure

        checkNotNull(failure) { "Expected ensureRuntime to fail when intellij backend is not available" }
        assertEquals("INTELLIJ_NOT_RUNNING", failure.code)
        assertTrue(failure.message.contains("IntelliJ"))
    }

    @Test
    fun `ensureRuntime falls back to standalone auto-start when no explicit backend`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-fallback")
        val launchedDescriptor = descriptor(workspaceRoot, pid = 300, backendName = "standalone")
        val manager = managerWith(
            runtimeStatuses = mapOf(
                launchedDescriptor.socketPath to listOf(
                    runtimeStatus(workspaceRoot, RuntimeState.READY, indexing = false),
                ),
            ),
            processLauncher = FakeProcessLauncher { _, logFile, _ ->
                writeDescriptor(descriptor = launchedDescriptor)
                StartedProcess(pid = launchedDescriptor.pid, logFile = logFile)
            },
            processLivenessChecker = { pid -> pid == 300L },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot, backendName = null))

        assertTrue(result.started)
        assertEquals("standalone", result.selected.descriptor.backendName)
    }

    @Test
    fun `ensureRuntime fails with STANDALONE_DISABLED when env var set and backend is standalone`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-standalone-disabled")
        val manager = managerWith(
            runtimeStatuses = emptyMap(),
            processLivenessChecker = { false },
            standaloneDisabled = { true },
        )

        val failure = runCatching {
            manager.ensureRuntime(options = runtimeOptions(workspaceRoot, backendName = "standalone"))
        }.exceptionOrNull() as? CliFailure

        checkNotNull(failure) { "Expected STANDALONE_DISABLED failure" }
        assertEquals("STANDALONE_DISABLED", failure.code)
        assertTrue(failure.message.contains("KAST_STANDALONE_DISABLE"))
    }

    @Test
    fun `ensureRuntime fails with STANDALONE_DISABLED when env var set and no backend running`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-standalone-disabled-no-backend")
        val manager = managerWith(
            runtimeStatuses = emptyMap(),
            processLivenessChecker = { false },
            standaloneDisabled = { true },
        )

        val failure = runCatching {
            manager.ensureRuntime(options = runtimeOptions(workspaceRoot, backendName = null))
        }.exceptionOrNull() as? CliFailure

        checkNotNull(failure) { "Expected STANDALONE_DISABLED failure when no backend and standalone disabled" }
        assertEquals("STANDALONE_DISABLED", failure.code)
        assertTrue(failure.message.contains("IntelliJ"))
    }

    @Test
    fun `ensureRuntime succeeds with intellij backend when standalone is disabled`() = runTest {
        val workspaceRoot = tempDir.resolve("workspace-intellij-only")
        val intellijDesc = writeDescriptor(
            descriptor = descriptor(workspaceRoot, pid = 400, backendName = "intellij"),
        )
        val manager = managerWith(
            runtimeStatuses = mapOf(
                intellijDesc.socketPath to listOf(
                    runtimeStatus(workspaceRoot, RuntimeState.READY, indexing = false, backendName = "intellij"),
                ),
            ),
            processLivenessChecker = { pid -> pid == 400L },
            standaloneDisabled = { true },
        )

        val result = manager.ensureRuntime(options = runtimeOptions(workspaceRoot, backendName = null))

        assertFalse(result.started)
        assertEquals("intellij", result.selected.descriptor.backendName)
    }

    // -- helpers --

    private fun managerWith(
        runtimeStatuses: Map<String, List<RuntimeStatusResponse>>,
        processLauncher: ProcessLauncher = FakeProcessLauncher(),
        processLivenessChecker: (Long) -> Boolean = { false },
        standaloneDisabled: () -> Boolean = { false },
    ) = WorkspaceRuntimeManager(
        rpcClient = FakeRuntimeRpcClient(runtimeStatuses),
        processLauncher = processLauncher,
        processLivenessChecker = processLivenessChecker,
        standaloneDisabled = standaloneDisabled,
        envLookup = envLookup,
    )

    private fun runtimeOptions(
        workspaceRoot: Path,
        backendName: String? = "standalone",
    ): RuntimeCommandOptions = RuntimeCommandOptions(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        waitTimeoutMillis = 2_000L,
    )

    private fun descriptor(
        workspaceRoot: Path,
        pid: Long,
        backendName: String = "standalone",
    ): ServerInstanceDescriptor = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot.toString(),
        backendName = backendName,
        backendVersion = "0.1.0-SNAPSHOT",
        socketPath = workspaceRoot.resolve(".kast/socket-$backendName").toString(),
        pid = pid,
    )

    private fun runtimeStatus(
        workspaceRoot: Path,
        state: RuntimeState,
        indexing: Boolean,
        backendName: String = "standalone",
    ): RuntimeStatusResponse = RuntimeStatusResponse(
        state = state,
        healthy = true,
        active = true,
        indexing = indexing,
        backendName = backendName,
        backendVersion = "0.1.0-SNAPSHOT",
        workspaceRoot = workspaceRoot.toString(),
        message = state.name,
    )

    private fun writeDescriptor(
        descriptor: ServerInstanceDescriptor,
    ): ServerInstanceDescriptor {
        val daemonsFile = defaultDescriptorDirectory(envLookup).resolve("daemons.json")
        DescriptorRegistry(daemonsFile).register(descriptor)
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
                backendName = descriptor.backendName,
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
