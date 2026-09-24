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
    READINESS,
    RETIREMENT,
}

@Serializable
internal enum class HostedEndpointOutcome {
    STARTED,
    COMPLETED,
    QUALIFIED,
    REJECTED,
    CANCELLED,
}

internal fun interface HostedEndpointObserver {
    fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome)

    fun transport(observation: HostedTransportObservation) = Unit

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

/** Project-owned carrier composing passive reads and an explicitly requested workspace lifecycle. */
@Service(Service.Level.PROJECT)
class HostedEndpointService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private enum class EndpointRetirement {
        LIVE,
        RETIRED,
    }

    private val endpointRetirement = java.util.concurrent.atomic.AtomicReference(EndpointRetirement.LIVE)

    internal fun lifecycleEndpointRetired(): Boolean = endpointRetirement.get() == EndpointRetirement.RETIRED

    internal val lifecycleAdmission = io.github.amichne.kast.runtime.hosted.lifecycle.HostedProjectAdmission()

    private sealed interface RefreshOwner {
        data object Starting : RefreshOwner

        data class Available(val value: io.github.amichne.kast.runtime.hosted.workspace.HostedWorkspaceRefresh) :
            RefreshOwner

        data object Retired : RefreshOwner
    }

    private val refreshOwner = java.util.concurrent.atomic.AtomicReference<RefreshOwner>(RefreshOwner.Starting)
    private val modelTracker = java.util.concurrent.atomic.AtomicReference<HostedGradleChangeTracker?>()

    internal fun lifecycleModelRevision(): Long? = modelTracker.get()?.currentRevision()

    internal fun lifecycleShouldReloadModel(): Boolean = modelTracker.get()?.needsOpeningImport() ?: true

    internal fun lifecycleModelImported(startedAt: Long) {
        modelTracker.get()?.modelImported(startedAt)
    }

    internal fun lifecycleInitialImport(
        requestId: String
    ): io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult =
        when (val owner = refreshOwner.get()) {
            is RefreshOwner.Available -> owner.value.initialImport(requestId)
            else ->
                io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult.Rejected(
                    io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure.ADMISSION_REJECTED
                )
        }

    internal fun lifecycleHasWork(): Boolean =
        when (val owner = refreshOwner.get()) {
            RefreshOwner.Starting -> true
            RefreshOwner.Retired -> false
            is RefreshOwner.Available -> owner.value.hasWork()
        }

    internal fun lifecycleRefreshReady(): Boolean = refreshOwner.get() is RefreshOwner.Available

    internal fun lifecycleRefresh(
        command: io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
    ): io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult =
        when (val owner = refreshOwner.get()) {
            RefreshOwner.Starting ->
                io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult.Rejected(
                    io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure.ADMISSION_REJECTED
                )
            RefreshOwner.Retired ->
                io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult.Rejected(
                    io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure.DISPOSED
                )
            is RefreshOwner.Available -> owner.value.execute(command).result
        }

    private val query = project.getService(HostedQueryService::class.java)
    private val changes = HostedChangeCoordinator(project, query)
    private val observer =
        object : HostedEndpointObserver {
            override fun transport(observation: HostedTransportObservation) {
                Logger.getInstance(HostedEndpointService::class.java)
                    .info("kast_transport " + Json { encodeDefaults = true }.encodeToString(observation))
            }

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
                            limits = limits,
                        )
                ) {
                    is Refinement.Refined -> opened.value
                    is Refinement.Rejected -> {
                        observer.rejected(HostedEndpointStage.BIND, opened.failure)
                        return@launch
                    }
                }
            val refresh =
                io.github.amichne.kast.runtime.hosted.workspace.HostedWorkspaceRefresh(project, root, query, scope)
            val gradleChanges = HostedGradleChangeTracker(project, root)
            modelTracker.set(gradleChanges)
            refreshOwner.set(RefreshOwner.Available(refresh))
            observer.observe(HostedEndpointStage.BIND, HostedEndpointOutcome.COMPLETED)
            try {
                serveHostedListener(owner.server, observer, limits) { request ->
                    if (!lifecycleAdmission.enter()) HostedResponse.Rejected(HostedEndpointFailure.PLATFORM_UNAVAILABLE)
                    else
                        try {
                            dispatch(root, request, continuations, limits, refresh, gradleChanges)
                        } finally {
                            lifecycleAdmission.leave()
                        }
                }
            } finally {
                withContext(NonCancellable) {
                    observer.observe(HostedEndpointStage.RETIREMENT, HostedEndpointOutcome.STARTED)
                    try {
                        try {
                            refreshOwner.set(RefreshOwner.Retired)
                            modelTracker.set(null)
                            gradleChanges.dispose()
                            refresh.dispose()
                            changes.close()
                            query.detach()
                        } finally {
                            continuations.retire()
                            owner.close()
                            endpointRetirement.set(EndpointRetirement.RETIRED)
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
        refresh: io.github.amichne.kast.runtime.hosted.workspace.HostedWorkspaceRefresh,
        gradleChanges: HostedGradleChangeTracker,
    ): HostedResponse {
        if (request.root != root) {
            return HostedResponse.Rejected(HostedEndpointFailure.WRONG_ROOT)
        }
        if (request !is HostedRequest.Describe && request !is HostedRequest.Refresh) {
            val refreshFailure = awaitHostedVfsRefresh(project, root).failure()
            if (refreshFailure != null) return HostedResponse.Rejected(refreshFailure)
            if (gradleChanges.needsModelReload()) {
                observer.rejected(HostedEndpointStage.READINESS, HostedEndpointFailure.MODEL_REFRESH_REQUIRED)
                return HostedResponse.Rejected(HostedEndpointFailure.MODEL_REFRESH_REQUIRED)
            }
        }
        return when (request) {
            is HostedRequest.Refresh -> HostedResponse.Completed(Json.encodeToString(refresh.execute(request.command)))
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
                                readiness = observeEndpointReadiness(observer) { query.readiness(root) },
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
            is HostedRequest.Read -> dispatchRead(request, continuations)
        }
    }

    private suspend fun dispatchRead(
        request: HostedRequest.Read,
        continuations: io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations,
    ): HostedResponse =
        when (
            val result =
                retryPresemanticIndexing(
                    read = {
                        query.read(
                            query.endpoint,
                            request.root,
                            outcome = { it.outcome },
                            executionBudget = request.executionBudget(),
                            publication = hostedReadPublicationAdmission,
                            completion = request.completionPolicy(),
                        ) { context ->
                            evaluateHostedCanonicalQuery(project, context, request, continuations)
                        }
                    },
                    wait = { awaitHostedSmartMode(project) },
                )
        ) {
            is HostedSemanticReadResult.Completed -> result.value
            is HostedSemanticReadResult.Rejected ->
                HostedResponse.ReadRejected(result.failure, result.stage, result.executionBudget)
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
    val readiness: io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument,
    val type: String = "KAST_IDE_HOST",
    val protocol: Int = HostedEndpointCapabilities.protocol,
    val indexAuthority: String = "existing_ide_kotlin_stub_index",
)

/** Readiness telemetry reports only the finite outcome of passive admission observation. */
internal fun observeEndpointReadiness(
    observer: HostedEndpointObserver,
    observe: () -> io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument,
): io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument {
    observer.observe(HostedEndpointStage.READINESS, HostedEndpointOutcome.STARTED)
    return observe().also { result ->
        observer.observe(
            HostedEndpointStage.READINESS,
            when (result) {
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument.AdmissionReady ->
                    HostedEndpointOutcome.COMPLETED
                is io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument.Unavailable ->
                    HostedEndpointOutcome.REJECTED
            },
        )
    }
}
