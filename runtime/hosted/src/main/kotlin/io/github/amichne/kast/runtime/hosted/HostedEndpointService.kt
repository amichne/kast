package io.github.amichne.kast.runtime.hosted

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryWire
import kotlinx.coroutines.*
import java.nio.channels.Channels
import java.nio.file.Path

internal enum class HostedEndpointStage { BIND, REQUEST, RETIREMENT }
internal enum class HostedEndpointOutcome { STARTED, COMPLETED, REJECTED, CANCELLED }
internal fun interface HostedEndpointObserver { fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) }

/** Platform-owned carrier; it opens no project, imports no model, and creates no isolated worker. */
@Service(Service.Level.PROJECT)
class HostedEndpointService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val query = project.getService(HostedQueryService::class.java)
    private val observer = HostedEndpointObserver { stage, outcome ->
        Logger.getInstance(HostedEndpointService::class.java).info("kast_hosted stage=${stage.name} outcome=${outcome.name}")
    }
    private val job = scope.launch(Dispatchers.IO) {
        observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.STARTED)
        val root = try {
            val basePath = project.basePath
            if (basePath == null) {
                observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.REJECTED)
                return@launch
            }
            when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(basePath))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> {
                    observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.REJECTED)
                    return@launch
                }
            }
        } catch (_: java.nio.file.InvalidPathException) {
            observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.REJECTED)
            return@launch
        }
        val owner = when (val opened = OwnedHostedEndpoint.open(OwnedHostedEndpoint.directory(Path.of(System.getProperty("user.home")), root), root)) {
            is Refinement.Refined -> opened.value
            is Refinement.Rejected -> { observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.REJECTED); return@launch }
        }
        observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.COMPLETED)
        try {
            while (isActive) {
                val connection = runInterruptible(Dispatchers.IO) { owner.server.accept() }
                connection.use { client ->
                    serveHostedConnection(Channels.newInputStream(client), Channels.newOutputStream(client), observer) {
                        dispatch(root, it)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.STARTED)
                try {
                    try { query.detach() } finally { owner.close() }
                    observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.COMPLETED)
                } catch (_: java.io.IOException) {
                    observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.REJECTED)
                }
            }
        }
    }

    private suspend fun dispatch(root: CanonicalWorkspaceRoot, request: HostedRequest): String {
        if (request.root != root) return HostedRequests.rejected(HostedEndpointFailure.WRONG_ROOT)
        return when (request) {
            is HostedRequest.Describe -> Gson().toJson(mapOf(
                "type" to "KAST_IDE_HOST", "protocol" to 1, "root" to root.value,
                "hostPid" to ProcessHandle.current().pid(), "indexAuthority" to "existing_ide_kotlin_stub_index",
                "operations" to listOf("DESCRIBE", "CLASS_LOOKUP", "DIRECT_SUPERTYPE"),
            ))
            is HostedRequest.Classes -> HostedQueryWire.encode(query.lookup(query.endpoint, request.lookup))
            is HostedRequest.Supertype -> HostedQueryWire.encode(query.query(query.endpoint, request.selection))
        }
    }

    override fun dispose() { query.dispose(); job.cancel() }
}

class HostedEndpointStartup : ProjectActivity {
    override suspend fun execute(project: Project) { project.getService(HostedEndpointService::class.java) }
}
