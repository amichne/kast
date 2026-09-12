package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryResult
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryWire
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.nio.channels.Channels
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class HostedEndpointStage {
    BIND,
    RECLAMATION_ADMISSION,
    RECLAMATION_RETIREMENT,
    ACCEPT,
    REQUEST,
    RETIREMENT,
}

internal enum class HostedEndpointOutcome {
    STARTED,
    COMPLETED,
    QUALIFIED,
    REJECTED,
    CANCELLED,
}

internal fun interface HostedEndpointObserver {
    fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome)

    fun responded(response: HostedResponse) {
        when (response) {
            is HostedResponse.Rejected -> rejected(HostedEndpointStage.REQUEST, response.failure)
            else ->
                observe(
                    HostedEndpointStage.REQUEST,
                    when (response.outcome) {
                        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.EVALUATED,
                        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.COMPLETE ->
                            HostedEndpointOutcome.COMPLETED
                        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.QUALIFIED ->
                            HostedEndpointOutcome.QUALIFIED
                        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.REJECTED ->
                            HostedEndpointOutcome.REJECTED
                    },
                )
        }
    }

    fun rejected(stage: HostedEndpointStage, failure: HostedEndpointFailure) {
        observe(stage, HostedEndpointOutcome.REJECTED)
    }
}

/** Platform-owned carrier; it opens no project, imports no model, and creates no isolated worker. */
@Service(Service.Level.PROJECT)
class HostedEndpointService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val query = project.getService(HostedQueryService::class.java)
    private val changes = HostedChangeCoordinator(project, query)
    private val observer =
        object : HostedEndpointObserver {
            override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) {
                Logger.getInstance(HostedEndpointService::class.java)
                    .info("kast_hosted stage=${stage.name} outcome=${outcome.name}")
            }

            override fun rejected(stage: HostedEndpointStage, failure: HostedEndpointFailure) {
                Logger.getInstance(HostedEndpointService::class.java)
                    .info("kast_hosted stage=${stage.name} outcome=REJECTED failure=${failure.name}")
            }
        }
    private val job =
        scope.launch(Dispatchers.IO) {
            observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.STARTED)
            val limits =
                when (val configuration = query.readConfiguration) {
                    is Refinement.Refined -> configuration.value
                    is Refinement.Rejected -> {
                        Logger.getInstance(HostedEndpointService::class.java)
                            .info("kast_hosted stage=CONFIGURATION outcome=REJECTED failure=${configuration.failure}")
                        observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.REJECTED)
                        return@launch
                    }
                }
            val continuations = io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations(limits)
            val root =
                try {
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
            val owner =
                when (
                    val opened =
                        OwnedHostedEndpoint.open(
                            directory = OwnedHostedEndpoint.directory(Path.of(System.getProperty("user.home")), root),
                            root = root,
                            host = query.hostLifetime,
                            observer = observer,
                        )
                ) {
                    is Refinement.Refined -> opened.value
                    is Refinement.Rejected -> {
                        observer.rejected(HostedEndpointStage.BIND, opened.failure)
                        return@launch
                    }
                }
            observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.COMPLETED)
            try {
                while (isActive) {
                    val connection =
                        when (val accepted = acceptHostedConnection(observer, accept = owner.server::accept)) {
                            is Refinement.Refined -> accepted.value
                            is Refinement.Rejected -> break
                        }
                    connection.use { client ->
                        serveHostedConnection(
                            Channels.newInputStream(client),
                            Channels.newOutputStream(client),
                            observer,
                            limits,
                        ) {
                            when (
                                val result =
                                    dispatchUntilPeerTermination(client::awaitHostedPeerTermination) {
                                        dispatch(root, it, continuations, limits)
                                    }
                            ) {
                                is HostedPeerDispatch.Completed -> result.response
                                is HostedPeerDispatch.Rejected ->
                                    HostedResponse.Rejected(
                                        when (result.termination) {
                                            HostedPeerTermination.DISCONNECTED -> HostedEndpointFailure.IO_UNAVAILABLE
                                            HostedPeerTermination.EXTRA_INPUT -> HostedEndpointFailure.INVALID_REQUEST
                                        }
                                    )
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.STARTED)
                    try {
                        try {
                            changes.close()
                            query.detach()
                        } finally {
                            continuations.retire()
                            owner.close()
                        }
                        observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.COMPLETED)
                    } catch (_: java.io.IOException) {
                        observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.REJECTED)
                    }
                }
            }
        }

    private suspend fun dispatch(
        root: CanonicalWorkspaceRoot,
        request: HostedRequest,
        continuations: io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations,
        limits: io.github.amichne.kast.kernel.ReadLimits,
    ): HostedResponse {
        if (request.root != root) {
            return HostedResponse.Rejected(HostedEndpointFailure.WRONG_ROOT)
        }
        return when (request) {
            is HostedRequest.PrepareApproval -> changes.prepare(request)
            is HostedRequest.ApplyChange -> changes.apply(request)
            is HostedRequest.RecoverChange -> changes.recover(request)
            is HostedRequest.Describe ->
                HostedResponse.Completed(
                    Json { encodeDefaults = true }
                        .encodeToString(
                            HostedDescriptionDocument(
                                root = root.value,
                                hostPid = ProcessHandle.current().pid(),
                                host = query.hostLifetime.value.toString(),
                                querySchema = HostedReadCapabilities.querySchema,
                                operations = HostedEndpointCapabilities.operations,
                            )
                        )
                )
            is HostedRequest.Classes ->
                when (val result = query.lookup(query.endpoint, request.lookup)) {
                    is io.github.amichne.kast.workspace.intellij.read.hosted.HostedIndexResult.Rejected ->
                        HostedResponse.ReadRejected(result.failure, result.stage)
                    is io.github.amichne.kast.workspace.intellij.read.hosted.HostedIndexResult.Published ->
                        HostedResponse.Completed(HostedQueryWire.encode(result, limits))
                }
            is HostedRequest.Supertype ->
                when (val result = query.query(query.endpoint, request.selection)) {
                    is HostedQueryResult.Rejected -> HostedResponse.ReadRejected(result.failure, result.stage)
                    is HostedQueryResult.Published -> HostedResponse.Completed(HostedQueryWire.encode(result))
                }
            is HostedRequest.PlanChange -> planHostedChange(project, query, request)
            is HostedRequest.Read ->
                when (
                    val result =
                        query.read(query.endpoint, root, outcome = { it.outcome }) { context ->
                            evaluateHostedCanonicalQuery(project, context, request, continuations)
                        }
                ) {
                    is HostedSemanticReadResult.Completed -> result.value
                    is HostedSemanticReadResult.Rejected -> HostedResponse.ReadRejected(result.failure, result.stage)
                }
        }
    }

    override fun dispose() {
        changes.close()
        query.dispose()
        job.cancel()
    }
}

class HostedEndpointStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(HostedEndpointService::class.java)
    }
}

@Serializable
private data class HostedDescriptionDocument(
    val root: String,
    val hostPid: Long,
    val host: String,
    val querySchema: String,
    val operations: List<String>,
    val type: String = "KAST_IDE_HOST",
    val protocol: Int = HostedEndpointCapabilities.protocol,
    val indexAuthority: String = "existing_ide_kotlin_stub_index",
)
