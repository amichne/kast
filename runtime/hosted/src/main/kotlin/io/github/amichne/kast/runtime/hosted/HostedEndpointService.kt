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
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryResult
import kotlinx.coroutines.*
import java.nio.channels.Channels
import java.nio.file.Path

internal enum class HostedEndpointStage { BIND, ACCEPT, REQUEST, RETIREMENT }
internal enum class HostedEndpointOutcome { STARTED, COMPLETED, REJECTED, CANCELLED }
internal fun interface HostedEndpointObserver {
    fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome)
    fun rejected(stage: HostedEndpointStage, failure: HostedEndpointFailure) { observe(stage, HostedEndpointOutcome.REJECTED) }
}

/** Platform-owned carrier; it opens no project, imports no model, and creates no isolated worker. */
@Service(Service.Level.PROJECT)
class HostedEndpointService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val query = project.getService(HostedQueryService::class.java)
    private val observer = object : HostedEndpointObserver {
        override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) {
            Logger.getInstance(HostedEndpointService::class.java).info("kast_hosted stage=${stage.name} outcome=${outcome.name}")
        }
        override fun rejected(stage: HostedEndpointStage, failure: HostedEndpointFailure) {
            Logger.getInstance(HostedEndpointService::class.java).info("kast_hosted stage=${stage.name} outcome=REJECTED failure=${failure.name}")
        }
    }
    private val job = scope.launch(Dispatchers.IO) {
        observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.STARTED)
        val limits = when (val configuration = query.readConfiguration) {
            is Refinement.Refined -> configuration.value
            is Refinement.Rejected -> {
                Logger.getInstance(HostedEndpointService::class.java).info("kast_hosted stage=CONFIGURATION outcome=REJECTED failure=${configuration.failure}")
                observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.REJECTED)
                return@launch
            }
        }
        val continuations = io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations(limits)
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
        val owner = when (val opened = OwnedHostedEndpoint.open(OwnedHostedEndpoint.directory(Path.of(System.getProperty("user.home")), root), root, query.hostLifetime)) {
            is Refinement.Refined -> opened.value
            is Refinement.Rejected -> { observer.rejected(HostedEndpointStage.BIND, opened.failure); return@launch }
        }
        observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.COMPLETED)
        try {
            while (isActive) {
                val connection = when (val accepted = acceptHostedConnection(observer, accept = owner.server::accept)) {
                    is Refinement.Refined -> accepted.value
                    is Refinement.Rejected -> break
                }
                connection.use { client ->
                    serveHostedConnection(Channels.newInputStream(client), Channels.newOutputStream(client), observer, limits) {
                        when (val result = dispatchUntilPeerTermination(client::awaitHostedPeerTermination) { dispatch(root, it, continuations, limits) }) {
                            is HostedPeerDispatch.Completed -> result.response
                            is HostedPeerDispatch.Rejected -> HostedRequests.rejected(when (result.termination) {
                                HostedPeerTermination.DISCONNECTED -> HostedEndpointFailure.IO_UNAVAILABLE
                                HostedPeerTermination.EXTRA_INPUT -> HostedEndpointFailure.INVALID_REQUEST
                            })
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.STARTED)
                try {
                    try { query.detach() } finally { continuations.retire(); owner.close() }
                    observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.COMPLETED)
                } catch (_: java.io.IOException) {
                    observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.REJECTED)
                }
            }
        }
    }

    private suspend fun dispatch(root: CanonicalWorkspaceRoot, request: HostedRequest, continuations: io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations, limits: io.github.amichne.kast.kernel.ReadLimits): String {
        if (request.root != root) {
            observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.WRONG_ROOT)
            return HostedRequests.rejected(HostedEndpointFailure.WRONG_ROOT)
        }
        return when (request) {
            is HostedRequest.Describe -> Gson().toJson(mapOf(
                "type" to "KAST_IDE_HOST", "protocol" to 2, "root" to root.value,
                "hostPid" to ProcessHandle.current().pid(), "indexAuthority" to "existing_ide_kotlin_stub_index",
                "host" to query.hostLifetime.value.toString(), "querySchema" to HostedReadCapabilities.querySchema,
                "operations" to HostedReadCapabilities.operations,
            ))
            is HostedRequest.Classes -> HostedQueryWire.encode(query.lookup(query.endpoint, request.lookup), limits)
            is HostedRequest.Supertype -> HostedQueryWire.encode(query.query(query.endpoint, request.selection))
            is HostedRequest.Read -> when (val result = query.read(query.endpoint, root) { context ->
                evaluateHostedCanonicalQuery(project, context, request, continuations)
            }) {
                is HostedSemanticReadResult.Completed -> result.value
                is HostedSemanticReadResult.Rejected -> HostedQueryWire.encode(HostedQueryResult.Rejected(result.failure, result.stage))
            }
        }
    }

    override fun dispose() { query.dispose(); job.cancel() }
}

class HostedEndpointStartup : ProjectActivity {
    override suspend fun execute(project: Project) { project.getService(HostedEndpointService::class.java) }
}
