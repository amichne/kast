package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.DescriptorRegistry
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.client.defaultDescriptorDirectory
import io.github.amichne.kast.cli.options.SmokeOptions
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SmokeCommandSupportTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var configHome: Path
    private lateinit var envLookup: (String) -> String?

    @BeforeEach
    fun setUp() {
        configHome = tempDir.resolve("config-home")
        envLookup = mapOf("KAST_CONFIG_HOME" to configHome.toString())::get
    }

    // ---------------------------------------------------------------------------
    // No runtime manager (null) — all backend checks skip
    // ---------------------------------------------------------------------------

    @Test
    fun `smoke with no runtime manager passes cli checks and skips backend checks`() = runTest {
        val support = SmokeCommandSupport(runtimeManager = null)

        val report = support.run(smokeOptions())

        assertTrue(report.ok, "Report should be ok when only skips occur")
        assertEquals(0, report.failCount)
        assertTrue(report.skipCount >= 1)
        assertCheckPasses(report, "cli-version")
        assertCheckPasses(report, "cli-help")
        assertCheckSkipped(report, "workspace-ensure")
    }

    @Test
    fun `smoke report cliVersion is populated`() = runTest {
        val support = SmokeCommandSupport(runtimeManager = null)

        val report = support.run(smokeOptions())

        assertNotNull(report.cliVersion)
        assertTrue(report.cliVersion.isNotBlank())
    }

    @Test
    fun `smoke report workspaceRoot matches options`() = runTest {
        val support = SmokeCommandSupport(runtimeManager = null)
        val workspace = tempDir.resolve("my-workspace")

        val report = support.run(smokeOptions(workspaceRoot = workspace))

        assertEquals(workspace.toAbsolutePath().normalize().toString(), report.workspaceRoot)
    }

    // ---------------------------------------------------------------------------
    // No backend running — workspace-ensure skips, backend-capabilities skips
    // ---------------------------------------------------------------------------

    @Test
    fun `smoke skips backend checks when no daemon is registered`() = runTest {
        val manager = managerWith(runtimeStatuses = emptyMap(), processLivenessChecker = { false })
        val support = SmokeCommandSupport(runtimeManager = manager)

        val report = support.run(smokeOptions())

        assertTrue(report.ok, "Missing backend should produce skips, not failures")
        assertEquals(0, report.failCount)
        assertCheckPasses(report, "cli-version")
        assertCheckPasses(report, "cli-help")
        assertCheckSkipped(report, "workspace-ensure")
        assertCheckSkipped(report, "backend-capabilities")
    }

    @Test
    fun `smoke skips include actionable kast daemon start hint when no daemon`() = runTest {
        val manager = managerWith(runtimeStatuses = emptyMap(), processLivenessChecker = { false })
        val support = SmokeCommandSupport(runtimeManager = manager)
        val workspace = tempDir.resolve("no-daemon")

        val report = support.run(smokeOptions(workspaceRoot = workspace))

        val ensureCheck = report.checks.first { it.name == "workspace-ensure" }
        assertNotNull(ensureCheck.message)
        assertTrue(
            ensureCheck.message.orEmpty().contains("kast daemon start"),
            "Skip message should mention 'kast daemon start', was: ${ensureCheck.message}",
        )
    }

    // ---------------------------------------------------------------------------
    // Backend running and READY — all checks pass
    // ---------------------------------------------------------------------------

    @Test
    fun `smoke passes all checks when backend is READY`() = runTest {
        val workspace = tempDir.resolve("workspace-ready")
        val descriptor = writeDescriptor(descriptor(workspaceRoot = workspace, pid = 101))
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(readyStatus(workspace)),
            ),
            processLivenessChecker = { pid -> pid == 101L },
        )
        val support = SmokeCommandSupport(runtimeManager = manager)

        val report = support.run(smokeOptions(workspaceRoot = workspace))

        assertTrue(report.ok)
        assertEquals(0, report.failCount)
        assertEquals(0, report.skipCount)
        assertCheckPasses(report, "cli-version")
        assertCheckPasses(report, "cli-help")
        assertCheckPasses(report, "workspace-ensure")
        assertCheckPasses(report, "backend-capabilities")
    }

    @Test
    fun `workspace-ensure message includes backend name and state when running`() = runTest {
        val workspace = tempDir.resolve("workspace-message")
        val descriptor = writeDescriptor(descriptor(workspaceRoot = workspace, pid = 102))
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(readyStatus(workspace)),
            ),
            processLivenessChecker = { pid -> pid == 102L },
        )
        val support = SmokeCommandSupport(runtimeManager = manager)

        val report = support.run(smokeOptions(workspaceRoot = workspace))

        val ensureCheck = report.checks.first { it.name == "workspace-ensure" }
        assertTrue(ensureCheck.message!!.contains("standalone"), "Should mention backend name")
    }

    @Test
    fun `backend-capabilities message includes capability counts`() = runTest {
        val workspace = tempDir.resolve("workspace-caps")
        val descriptor = writeDescriptor(descriptor(workspaceRoot = workspace, pid = 103))
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(readyStatus(workspace)),
            ),
            processLivenessChecker = { pid -> pid == 103L },
        )
        val support = SmokeCommandSupport(runtimeManager = manager)

        val report = support.run(smokeOptions(workspaceRoot = workspace))

        val capsCheck = report.checks.first { it.name == "backend-capabilities" }
        assertTrue(capsCheck.message.orEmpty().contains("read"), "Should mention read capabilities")
        assertTrue(capsCheck.message.orEmpty().contains("mutation"), "Should mention mutation capabilities")
    }

    // ---------------------------------------------------------------------------
    // Backend running but in INDEXING — ensure accepts indexing, still passes
    // ---------------------------------------------------------------------------

    @Test
    fun `smoke accepts INDEXING backend as a passing workspace-ensure`() = runTest {
        val workspace = tempDir.resolve("workspace-indexing")
        val descriptor = writeDescriptor(descriptor(workspaceRoot = workspace, pid = 201))
        val manager = managerWith(
            runtimeStatuses = mapOf(
                descriptor.socketPath to listOf(indexingStatus(workspace)),
            ),
            processLivenessChecker = { pid -> pid == 201L },
        )
        val support = SmokeCommandSupport(runtimeManager = manager)

        val report = support.run(smokeOptions(workspaceRoot = workspace))

        assertTrue(report.ok)
        assertCheckPasses(report, "workspace-ensure")
    }

    // ---------------------------------------------------------------------------
    // smoke output format — JSON and markdown
    // ---------------------------------------------------------------------------

    @Test
    fun `smoke report is serializable as JSON`() = runTest {
        val support = SmokeCommandSupport(runtimeManager = null)
        val report = support.run(smokeOptions())

        val json = defaultCliJson()
        val encoded = json.encodeToString(SmokeReport.serializer(), report)
        val decoded = json.decodeFromString(SmokeReport.serializer(), encoded)

        assertEquals(report.passCount, decoded.passCount)
        assertEquals(report.failCount, decoded.failCount)
        assertEquals(report.skipCount, decoded.skipCount)
        assertEquals(report.ok, decoded.ok)
    }

    @Test
    fun `smoke markdown report contains expected sections`() = runTest {
        val support = SmokeCommandSupport(runtimeManager = null)
        val report = support.run(smokeOptions())

        val markdown = report.toMarkdown()

        assertTrue(markdown.contains("Kast Smoke Report"))
        assertTrue(markdown.contains("cli-version"))
        assertTrue(markdown.contains("cli-help"))
        assertTrue(markdown.contains("PASS"))
        assertTrue(markdown.contains("SKIP"))
    }

    @Test
    fun `smoke markdown report shows OK icon when no failures`() = runTest {
        val support = SmokeCommandSupport(runtimeManager = null)
        val report = support.run(smokeOptions())

        val markdown = report.toMarkdown()

        assertTrue(report.ok)
        assertTrue(markdown.contains("✅"), "OK report should show ✅ icon")
        assertFalse(markdown.contains("❌"), "OK report should not show ❌ icon")
    }

    @Test
    fun `smoke report ok is false when a check fails`() {
        val failReport = SmokeReport(
            workspaceRoot = tempDir.toString(),
            cliVersion = "dev",
            checks = listOf(
                SmokeCheck("cli-version", SmokeCheckStatus.PASS),
                SmokeCheck("workspace-ensure", SmokeCheckStatus.FAIL, "daemon crashed"),
            ),
            passCount = 1,
            failCount = 1,
            skipCount = 0,
            ok = false,
        )

        assertFalse(failReport.ok)
        val markdown = failReport.toMarkdown()
        assertTrue(markdown.contains("❌ FAIL"))
        assertTrue(markdown.contains("❌"), "Failed report should show ❌ icon in header")
    }

    @Test
    fun `smoke produces FAIL for workspace-ensure when backend stays in STARTING state`() {
        // Uses runBlocking so real time passes and the RUNTIME_TIMEOUT fires after 500ms.
        // RUNTIME_TIMEOUT code != NO_BACKEND_AVAILABLE, so SmokeCommandSupport maps it to FAIL.
        kotlinx.coroutines.runBlocking {
            val workspace = tempDir.resolve("workspace-starting")
            val startingStatus = RuntimeStatusResponse(
                state = RuntimeState.STARTING,
                healthy = true,
                active = true,
                indexing = false,
                backendName = "standalone",
                backendVersion = "0.1.0-SNAPSHOT",
                workspaceRoot = workspace.toString(),
                message = "starting",
            )
            val descriptor = writeDescriptor(descriptor(workspaceRoot = workspace, pid = 999))
            val startingClient = object : RuntimeRpcClient {
                override fun runtimeStatus(descriptor: ServerInstanceDescriptor): RuntimeStatusResponse = startingStatus
                override fun capabilities(descriptor: ServerInstanceDescriptor): BackendCapabilities =
                    throw CliFailure(code = "NOT_READY", message = "backend still starting")
            }
            val manager = WorkspaceRuntimeManager(
                rpcClient = startingClient,
                processLivenessChecker = { pid -> pid == 999L },
                envLookup = envLookup,
            )
            val support = SmokeCommandSupport(runtimeManager = manager, runtimeWaitTimeoutMillis = 500L)

            val report = support.run(smokeOptions(workspaceRoot = workspace))

            assertFalse(report.ok, "Report should not be ok when backend stays in STARTING state")
            assertTrue(report.failCount >= 1, "Expected at least one FAIL")
            val ensureCheck = report.checks.first { it.name == "workspace-ensure" }
            assertEquals(SmokeCheckStatus.FAIL, ensureCheck.status)
        }
    }

    // ---------------------------------------------------------------------------
    // KastCli integration — kast smoke routes through KastCli correctly
    // ---------------------------------------------------------------------------

    @Test
    fun `kast smoke emits valid JSON report on stdout`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = KastCli().run(
            arrayOf("smoke", "--workspace-root=${tempDir.resolve("no-daemon")}"),
            stdout,
            stderr,
        )

        assertEquals(0, exitCode, "smoke should exit 0 even when no backend; stderr=$stderr")
        val report = defaultCliJson().decodeFromString(SmokeReport.serializer(), stdout.toString())
        assertTrue(report.ok, "No backend means skips, still ok=true")
        assertEquals(0, report.failCount)
    }

    @Test
    fun `kast smoke --format=markdown emits markdown text on stdout`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = KastCli().run(
            arrayOf("smoke", "--format=markdown"),
            stdout,
            stderr,
        )

        assertEquals(0, exitCode, "smoke markdown should exit 0; stderr=$stderr")
        val output = stdout.toString()
        assertTrue(output.contains("# Kast Smoke Report"))
        assertTrue(output.contains("cli-version"))
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun smokeOptions(workspaceRoot: Path = tempDir.resolve("workspace")) = SmokeOptions(
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
        fileFilter = null,
        sourceSetFilter = null,
        symbolFilter = null,
        format = SmokeOutputFormat.JSON,
    )

    private fun managerWith(
        runtimeStatuses: Map<String, List<RuntimeStatusResponse>>,
        processLivenessChecker: (Long) -> Boolean,
    ) = WorkspaceRuntimeManager(
        rpcClient = FakeSmokeRpcClient(runtimeStatuses),
        processLivenessChecker = processLivenessChecker,
        envLookup = envLookup,
    )

    private fun descriptor(
        workspaceRoot: Path,
        pid: Long,
        backendName: String = "standalone",
    ) = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot.toString(),
        backendName = backendName,
        backendVersion = "0.1.0-SNAPSHOT",
        socketPath = workspaceRoot.resolve(".kast/socket-$backendName").toString(),
        pid = pid,
    )

    private fun readyStatus(workspaceRoot: Path, backendName: String = "standalone") =
        RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = backendName,
            backendVersion = "0.1.0-SNAPSHOT",
            workspaceRoot = workspaceRoot.toString(),
            message = "ready",
        )

    private fun indexingStatus(workspaceRoot: Path, backendName: String = "standalone") =
        RuntimeStatusResponse(
            state = RuntimeState.INDEXING,
            healthy = true,
            active = true,
            indexing = true,
            backendName = backendName,
            backendVersion = "0.1.0-SNAPSHOT",
            workspaceRoot = workspaceRoot.toString(),
            message = "indexing",
        )

    private fun writeDescriptor(descriptor: ServerInstanceDescriptor): ServerInstanceDescriptor {
        val daemonsFile = defaultDescriptorDirectory(envLookup).resolve("daemons.json")
        DescriptorRegistry(daemonsFile).register(descriptor)
        return descriptor
    }

    private fun assertCheckPasses(report: SmokeReport, name: String) {
        val check = report.checks.firstOrNull { it.name == name }
        assertNotNull(check, "Expected check '$name' in report but it was missing")
        assertEquals(
            SmokeCheckStatus.PASS, check!!.status,
            "Expected check '$name' to PASS but was ${check.status}: ${check.message}",
        )
    }

    private fun assertCheckSkipped(report: SmokeReport, name: String) {
        val check = report.checks.firstOrNull { it.name == name }
        assertNotNull(check, "Expected check '$name' in report but it was missing")
        assertEquals(
            SmokeCheckStatus.SKIP, check!!.status,
            "Expected check '$name' to SKIP but was ${check.status}: ${check.message}",
        )
    }

    private class FakeSmokeRpcClient(
        runtimeStatuses: Map<String, List<RuntimeStatusResponse>>,
    ) : RuntimeRpcClient {
        private val queues = runtimeStatuses.mapValues { (_, v) -> ArrayDeque(v) }

        override fun runtimeStatus(descriptor: ServerInstanceDescriptor): RuntimeStatusResponse {
            val queue = queues[descriptor.socketPath]
                ?: throw CliFailure(code = "DAEMON_UNREACHABLE", message = "No status for ${descriptor.socketPath}")
            return if (queue.size > 1) queue.removeFirst() else checkNotNull(queue.firstOrNull())
        }

        override fun capabilities(descriptor: ServerInstanceDescriptor) = BackendCapabilities(
            backendName = descriptor.backendName,
            backendVersion = "0.1.0-SNAPSHOT",
            workspaceRoot = descriptor.workspaceRoot,
            readCapabilities = setOf(ReadCapability.DIAGNOSTICS, ReadCapability.FIND_REFERENCES),
            mutationCapabilities = setOf(MutationCapability.RENAME),
            limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
        )
    }
}
