package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProjectSession
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import kotlinx.coroutines.CoroutineScope

/** Explicit in-process endpoint. Construction never discovers, opens, repairs, or imports a project. */
@Service(Service.Level.PROJECT)
class HostedQueryService private constructor(
    private val project: Project,
    serviceScope: CoroutineScope,
    private val checkpoint: HostedReadCheckpoint,
) : Disposable {
    constructor(project: Project, serviceScope: CoroutineScope) : this(project, serviceScope, HostedReadCheckpoint.Unobserved)

    internal companion object {
        fun observed(project: Project, scope: CoroutineScope, checkpoint: HostedReadCheckpoint) =
            HostedQueryService(project, scope, checkpoint)
    }
    private val executor = HostedQueryExecutor(serviceScope)
    private val owner = Disposer.newDisposable("Kast hosted query epoch")
    private val session = AdmittedIdeProjectSession(owner)
    val endpoint: HostedQueryEndpoint get() = executor.endpoint

    /** Bounded exact-name discovery in the original IDE's already-maintained Kotlin index. */
    suspend fun lookup(
        endpoint: HostedQueryEndpoint,
        lookup: HostedClassLookup,
        candidate: IdeHostCompatibilityCandidate,
        policy: IdeHostCompatibilityPolicy,
    ): HostedIndexResult {
        if (ApplicationManager.getApplication().isReadAccessAllowed || ApplicationManager.getApplication().isDispatchThread) {
            return HostedIndexResult.Rejected(HostedQueryFailure.WRONG_THREAD)
        }
        return when (val execution = executor.execute(endpoint) { progress ->
            progress.advance(HostedQueryStage.PROJECT_ADMISSION)
            when (val admission = session.admit(project, lookup.root, candidate, policy)) {
                is ExistingProjectAdmission.Admitted -> admission.project.prepareHostedRead(
                    lookup.root, progress, checkpoint,
                    { retained, model -> readHostedClassIndex(retained, lookup, model) },
                    { retained, evidence -> verifyHostedDeclarations(retained, evidence.declarations) },
                )
                is ExistingProjectAdmission.Rejected -> HostedReadPreparation.Rejected(HostedQueryFailure.ProjectAdmission(admission.failure))
            }
        }) {
            is HostedExecution.Rejected -> HostedIndexResult.Rejected(execution.failure, execution.stage)
            is HostedExecution.Completed -> when (val prepared = execution.value) {
                is HostedReadPreparation.Prepared -> HostedIndexResult.Published(
                    HostedIndexPublication(endpoint, prepared.epoch, prepared.model, prepared.evidence),
                )
                is HostedReadPreparation.Rejected -> HostedIndexResult.Rejected(prepared.failure, execution.stage)
            }
        }
    }

    /** One bounded request, with a detached answer returned after all platform reads have ended. */
    suspend fun query(
        endpoint: HostedQueryEndpoint,
        selection: HostedKotlinSelection,
        candidate: IdeHostCompatibilityCandidate,
        policy: IdeHostCompatibilityPolicy,
    ): HostedQueryResult {
        if (ApplicationManager.getApplication().isReadAccessAllowed ||
            ApplicationManager.getApplication().isDispatchThread
        ) return HostedQueryResult.Rejected(HostedQueryFailure.WRONG_THREAD)
        return when (val execution = executor.execute(endpoint) { progress ->
            progress.advance(HostedQueryStage.PROJECT_ADMISSION)
            when (val admission = session.admit(project, selection.root, candidate, policy)) {
                is ExistingProjectAdmission.Admitted -> admission.project.prepareHostedQuery(selection, progress, checkpoint)
                is ExistingProjectAdmission.Rejected -> HostedReadPreparation.Rejected(
                    HostedQueryFailure.ProjectAdmission(admission.failure),
                )
            }
        }) {
            is HostedExecution.Rejected -> HostedQueryResult.Rejected(execution.failure, execution.stage)
            is HostedExecution.Completed -> when (val prepared = execution.value) {
                is HostedReadPreparation.Prepared -> HostedQueryResult.Published(
                    HostedQueryPublication(endpoint, prepared.epoch, prepared.model, prepared.evidence),
                )
                is HostedReadPreparation.Rejected -> HostedQueryResult.Rejected(prepared.failure, execution.stage)
            }
        }
    }

    /** Original owner only: completion proves all owned requests ended and listeners disconnected. */
    suspend fun detach(): HostedQueryRetirement {
        executor.retire()
        executor.drain()
        Disposer.dispose(owner)
        return HostedQueryRetirement.RETIRED
    }

    override fun dispose() {
        executor.retire()
        Disposer.dispose(owner)
    }
}

enum class HostedQueryRetirement { RETIRED }
