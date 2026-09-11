package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

class NativeFixtureProbeStartup : ProjectActivity, com.intellij.openapi.project.DumbAware {
    override suspend fun execute(project: Project) {
        val sandbox =
            when (val admission = ProbeSandbox.admit(project)) {
                is ProbeResult.Accepted -> admission.value
                is ProbeResult.Rejected -> return
            }
        val worker = ProbeWorker(project, sandbox)
        Disposer.register(project, worker)
        Thread(worker, "kast-isolated-native-fixture-probe").apply { isDaemon = true }.start()
    }
}

internal class ProbeSandbox private constructor(val root: Path, val project: Path) {
    fun valid(current: Project): Boolean =
        try {
            !current.isDisposed &&
                !current.isDefault &&
                Path.of(current.basePath.orEmpty()).toRealPath() == project &&
                root.toRealPath() == root &&
                root.parent == Path.of("/private/tmp").toRealPath() &&
                root.fileName.toString().matches(Regex("kast-a-[A-Za-z0-9_-]{6,32}")) &&
                Files.getPosixFilePermissions(root) == PosixFilePermissions.fromString("rwx------") &&
                project == root.resolve("workspace") &&
                project.toRealPath() == project &&
                configuredDirectoriesArePrivate()
        } catch (_: Exception) {
            false
        }

    private fun configuredDirectoriesArePrivate(): Boolean =
        listOf(
                "idea.config.path",
                "idea.system.path",
                "idea.plugins.path",
                "idea.log.path",
                "user.home",
                "java.io.tmpdir",
            )
            .all { key ->
                val path = Path.of(System.getProperty(key) ?: "").toRealPath()
                path != root && path.startsWith(root)
            }

    companion object {
        fun admit(project: Project): ProbeResult<ProbeSandbox> =
            try {
                val root = System.getProperty("kast.fixture.sandbox")?.let { Path.of(it).toRealPath() }
                val workspace = System.getProperty("kast.fixture.project")?.let { Path.of(it).toRealPath() }
                if (root == null || workspace == null) ProbeResult.Rejected(ProbeFailure.NOT_ENABLED)
                else {
                    val sandbox = ProbeSandbox(root, workspace)
                    if (sandbox.valid(project)) ProbeResult.Accepted(sandbox)
                    else ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
                }
            } catch (_: Exception) {
                ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
            }
    }
}

private class ProbeWorker(private val project: Project, private val sandbox: ProbeSandbox) :
    Runnable, com.intellij.openapi.Disposable, ProbeDispatchPorts {
    private val spool = sandbox.root.resolve("native-probe")
    private val requests = spool.resolve("requests")
    private val responses = spool.resolve("responses")
    private val readiness = ProbeSetupReadiness(project, sandbox)
    private val controls = NativeFixtureProbeControls(project, sandbox)
    private val watcher = sandbox.root.fileSystem.newWatchService()
    @Volatile private var stopped = false

    override fun run() {
        try {
            for (directory in listOf(spool, requests, responses)) {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(
                        directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                }
                if (
                    directory.toRealPath() != directory ||
                        Files.getPosixFilePermissions(directory) != PosixFilePermissions.fromString("rwx------")
                )
                    return
            }
            requests.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
            publish(spool.resolve("ready.json"), "{\"version\":1,\"outcome\":\"READY\"}")
            scan()
            while (!stopped && !project.isDisposed) {
                val key = watcher.take()
                key.pollEvents()
                scan()
                if (!key.reset()) return
            }
        } catch (_: Exception) {
            // Test-only spool failures close the capability; the caller observes its bounded timeout.
        } finally {
            watcher.close()
        }
    }

    private fun scan() {
        Files.newDirectoryStream(requests, "*.json").use { entries ->
            for (path in entries.take(MAXIMUM_BATCH_REQUESTS)) {
                if (stopped || !sandbox.valid(project)) return
                process(path)
            }
        }
    }

    private fun process(path: Path) {
        val id =
            try {
                UUID.fromString(path.fileName.toString().removeSuffix(".json"))
            } catch (_: IllegalArgumentException) {
                return
            }
        if (path.fileName.toString() != "$id.json" || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.getPosixFilePermissions(path) != PosixFilePermissions.fromString("rw-------")) return
        if (Files.exists(responses.resolve("$id.json"), LinkOption.NOFOLLOW_LINKS)) return
        val claimed = requests.resolve("$id.processing")
        if (Files.exists(claimed, LinkOption.NOFOLLOW_LINKS)) return
        Files.move(path, claimed, StandardCopyOption.ATOMIC_MOVE)
        val bytes = Files.newInputStream(claimed).use { it.readNBytes(MAXIMUM_REQUEST_BYTES + 1) }
        val request = ProbeRequest.decode(bytes, id)
        var command = "UNPARSED"
        var result: ProbeExecution = ProbeExecution.Rejected(ProbeFailure.NATIVE_UNAVAILABLE)
        when (request) {
            is ProbeResult.Rejected -> result = ProbeExecution.Rejected(request.failure)
            is ProbeResult.Accepted -> {
                command = request.value.command.name
                result = ProbeRequestDispatch.dispatch(request.value, this)
            }
        }
        publish(responses.resolve("$id.json"), encodeProbeResponse(id, command, result))
    }

    override fun executeNative(request: ProbeRequest): ProbeExecution {
        var result: ProbeExecution = ProbeExecution.Rejected(ProbeFailure.NATIVE_UNAVAILABLE)
        ApplicationManager.getApplication()
            .invokeAndWait(
                {
                    result = NativeFixtureProbeExecution(project, sandbox, controls).execute(request)
                },
                ModalityState.nonModal(),
            )
        return result
    }

    override fun awaitReadiness(request: ProbeRequest): ProbeExecution =
        readiness.await(request) {
            NativeFixtureProbeExecution(project, sandbox, controls).execute(request)
        }

    private fun publish(path: Path, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_RESPONSE_BYTES) return
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(temporary, bytes, StandardOpenOption.WRITE)
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun dispose() {
        stopped = true
        readiness.dispose()
        controls.dispose()
        watcher.close()
    }
}

private const val MAXIMUM_BATCH_REQUESTS = 16
