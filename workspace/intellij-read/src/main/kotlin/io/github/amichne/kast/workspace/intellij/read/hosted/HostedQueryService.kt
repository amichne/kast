package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.kernel.ReadLimitFailure
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProjectSession
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import io.github.amichne.kast.workspace.intellij.read.readHostedConfiguration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/** Explicit in-process endpoint. Construction never discovers, opens, repairs, or imports a project. */
@Service(Service.Level.PROJECT)
class HostedQueryService
private constructor(
    private val project: Project,
    serviceScope: CoroutineScope,
    private val checkpoint: HostedReadCheckpoint,
) : Disposable {
    constructor(
        project: Project,
        serviceScope: CoroutineScope,
    ) : this(project, serviceScope, HostedReadCheckpoint.Unobserved)

    internal companion object {
        fun observed(project: Project, scope: CoroutineScope, checkpoint: HostedReadCheckpoint) =
            HostedQueryService(project, scope, checkpoint)
    }

    private val executor = HostedQueryExecutor(serviceScope, ::hostedReadDiagnostics)
    private val owner = Disposer.newDisposable("Kast hosted query epoch")
    val readConfiguration: Refinement<ReadLimits, ReadLimitFailure> = readHostedConfiguration()
    private val configuredSession =
        when (val settings = readConfiguration) {
            is Refinement.Refined ->
                ConfiguredHostedSession.Ready(settings.value, AdmittedIdeProjectSession(owner, settings.value))
            is Refinement.Rejected ->
                ConfiguredHostedSession.Rejected(HostedQueryFailure.Configuration(settings.failure))
        }
    val hostLifetime = IdeReadHostLifetime.fromBoundary(UUID.randomUUID())
    private val liveAuthorities = HostedLiveReadAuthoritySession(hostLifetime)
    // A policy is retained admission authority, not just equal metadata. Reuse its
    // original proof for every request in this endpoint lifetime.
    private val packagedCompatibility by lazy(::packagedHostedCompatibility)
    val endpoint: HostedQueryEndpoint
        get() = executor.endpoint

    /** One permit and deadline for the complete plan; each adapter owns its individual short read. */
    suspend fun <Value> read(
        endpoint: HostedQueryEndpoint,
        root: CanonicalWorkspaceRoot,
        evaluate: suspend (HostedSemanticReadContext) -> Value,
    ): HostedSemanticReadResult<Value> {
        if (
            ApplicationManager.getApplication().isReadAccessAllowed ||
                ApplicationManager.getApplication().isDispatchThread
        ) {
            hostedReadDiagnostics().finish(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.WRONG_THREAD))
            return HostedSemanticReadResult.Rejected(
                HostedQueryFailure.WRONG_THREAD,
                HostedQueryStage.REQUEST_ADMISSION,
            )
        }
        val configured =
            when (val state = configuredSession) {
                is ConfiguredHostedSession.Ready -> state
                is ConfiguredHostedSession.Rejected -> {
                    hostedReadDiagnostics().finish(HostedDiagnosticOutcome.Rejected(state.failure))
                    return HostedSemanticReadResult.Rejected(state.failure, HostedQueryStage.REQUEST_ADMISSION)
                }
            }
        val session = configured.session
        return when (
            val execution =
                executor.execute(endpoint, configured.limits) { progress ->
                    progress.diagnostics?.bindHost(hostLifetime)
                    val compatibility =
                        when (val admitted = packagedCompatibility) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected -> return@execute HostedSemanticRead.Rejected(admitted.failure)
                        }
                    progress.advance(HostedQueryStage.PROJECT_ADMISSION)
                    val admitted =
                        when (
                            val admission = session.admit(project, root, compatibility.candidate, compatibility.policy)
                        ) {
                            is ExistingProjectAdmission.Admitted -> admission.project
                            is ExistingProjectAdmission.Rejected ->
                                return@execute HostedSemanticRead.Rejected(
                                    HostedQueryFailure.ProjectAdmission(admission.failure)
                                )
                        }
                    progress.advance(HostedQueryStage.EPOCH_OBSERVATION)
                    val epoch =
                        when (val observed = admitted.observeReadEpoch()) {
                            is ProjectReadEpochObservation.Observed -> observed.epoch
                            is ProjectReadEpochObservation.Rejected ->
                                return@execute HostedSemanticRead.Rejected(
                                    HostedQueryFailure.ReadEpoch(observed.failure)
                                )
                        }
                    progress.advance(HostedQueryStage.MODEL_CAPTURE)
                    val sourceScope =
                        when (
                            val captured = admitted.captureNamedGradleSourceScope(progress.observation, progress.limits)
                        ) {
                            is Refinement.Refined -> captured.value
                            is Refinement.Rejected ->
                                return@execute HostedSemanticRead.Rejected(
                                    HostedQueryFailure.NamedSourceScope(captured.failure)
                                )
                        }
                    val freshness =
                        when (val current = admitted.admitVfsPassiveRead(epoch)) {
                            is VfsPassiveReadAdmission.Admitted -> current.capability
                            is VfsPassiveReadAdmission.Rejected ->
                                return@execute HostedSemanticRead.Rejected(
                                    HostedQueryFailure.Freshness(current.failure)
                                )
                        }
                    val authority =
                        when (val current = liveAuthorities.admit(freshness)) {
                            is Refinement.Refined -> current.value
                            is Refinement.Rejected ->
                                return@execute HostedSemanticRead.Rejected(
                                    HostedQueryFailure.LiveAuthority(current.failure)
                                )
                        }
                    progress.diagnostics?.bind(authority.reference)
                    val context =
                        HostedSemanticReadContext(
                            authority,
                            sourceScope.model,
                            IntellijSemanticSourceFileAdmission(sourceScope::contains),
                            progress.observation,
                            progress.limits,
                        ) {
                            readAction {
                                when (val saved = checkSavedDocuments(project)) {
                                    is SavedDocuments.Rejected -> return@readAction Refinement.Rejected(saved.failure)
                                    SavedDocuments.Clean -> Unit
                                }
                                when (val current = admitted.admitVfsPassiveRead(epoch)) {
                                    is VfsPassiveReadAdmission.Admitted -> Refinement.Refined(Unit)
                                    is VfsPassiveReadAdmission.Rejected ->
                                        Refinement.Rejected(HostedQueryFailure.Freshness(current.failure))
                                }
                            }
                        }
                    try {
                        runHostedReadTransaction(progress, context::validate) { evaluate(context) }
                    } finally {
                        context.end()
                    }
                }
        ) {
            is HostedExecution.Rejected -> HostedSemanticReadResult.Rejected(execution.failure, execution.stage)
            is HostedExecution.Completed ->
                when (val result = execution.value) {
                    is HostedSemanticRead.Resolved -> HostedSemanticReadResult.Completed(result.evidence)
                    is HostedSemanticRead.Rejected -> HostedSemanticReadResult.Rejected(result.failure, execution.stage)
                }
        }
    }

    suspend fun lookup(endpoint: HostedQueryEndpoint, lookup: HostedClassLookup): HostedIndexResult =
        when (val compatibility = packagedCompatibility) {
            is io.github.amichne.kast.kernel.Refinement.Refined ->
                lookup(endpoint, lookup, compatibility.value.candidate, compatibility.value.policy)
            is io.github.amichne.kast.kernel.Refinement.Rejected -> HostedIndexResult.Rejected(compatibility.failure)
        }

    suspend fun query(endpoint: HostedQueryEndpoint, selection: HostedSupertypeSelection): HostedQueryResult =
        when (val compatibility = packagedCompatibility) {
            is io.github.amichne.kast.kernel.Refinement.Refined ->
                query(endpoint, selection, compatibility.value.candidate, compatibility.value.policy)
            is io.github.amichne.kast.kernel.Refinement.Rejected -> HostedQueryResult.Rejected(compatibility.failure)
        }

    /** Bounded exact-name discovery in the original IDE's already-maintained Kotlin index. */
    suspend fun lookup(
        endpoint: HostedQueryEndpoint,
        lookup: HostedClassLookup,
        candidate: IdeHostCompatibilityCandidate,
        policy: IdeHostCompatibilityPolicy,
    ): HostedIndexResult {
        if (
            ApplicationManager.getApplication().isReadAccessAllowed ||
                ApplicationManager.getApplication().isDispatchThread
        ) {
            hostedReadDiagnostics().finish(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.WRONG_THREAD))
            return HostedIndexResult.Rejected(HostedQueryFailure.WRONG_THREAD)
        }
        val configured =
            when (val state = configuredSession) {
                is ConfiguredHostedSession.Ready -> state
                is ConfiguredHostedSession.Rejected -> {
                    hostedReadDiagnostics().finish(HostedDiagnosticOutcome.Rejected(state.failure))
                    return HostedIndexResult.Rejected(state.failure)
                }
            }
        val session = configured.session
        return when (
            val execution =
                executor.execute(endpoint, configured.limits) { progress ->
                    progress.advance(HostedQueryStage.PROJECT_ADMISSION)
                    when (val admission = session.admit(project, lookup.root, candidate, policy)) {
                        is ExistingProjectAdmission.Admitted ->
                            admission.project.prepareHostedRead(
                                lookup.root,
                                progress,
                                checkpoint,
                                { retained, model -> readHostedClassIndex(retained, lookup, model, progress.limits) },
                                { retained, evidence -> verifyHostedDeclarations(retained, evidence.declarations) },
                            )
                        is ExistingProjectAdmission.Rejected ->
                            HostedReadPreparation.Rejected(HostedQueryFailure.ProjectAdmission(admission.failure))
                    }
                }
        ) {
            is HostedExecution.Rejected -> HostedIndexResult.Rejected(execution.failure, execution.stage)
            is HostedExecution.Completed ->
                when (val prepared = execution.value) {
                    is HostedReadPreparation.Prepared ->
                        HostedIndexResult.Published(
                            HostedIndexPublication(endpoint, prepared.epoch, prepared.model, prepared.evidence)
                        )
                    is HostedReadPreparation.Rejected -> HostedIndexResult.Rejected(prepared.failure, execution.stage)
                }
        }
    }

    /** One bounded request, with a detached answer returned after all platform reads have ended. */
    suspend fun query(
        endpoint: HostedQueryEndpoint,
        selection: HostedSupertypeSelection,
        candidate: IdeHostCompatibilityCandidate,
        policy: IdeHostCompatibilityPolicy,
    ): HostedQueryResult {
        if (
            ApplicationManager.getApplication().isReadAccessAllowed ||
                ApplicationManager.getApplication().isDispatchThread
        )
            return HostedQueryResult.Rejected(HostedQueryFailure.WRONG_THREAD)
        val configured =
            when (val state = configuredSession) {
                is ConfiguredHostedSession.Ready -> state
                is ConfiguredHostedSession.Rejected -> {
                    hostedReadDiagnostics().finish(HostedDiagnosticOutcome.Rejected(state.failure))
                    return HostedQueryResult.Rejected(state.failure)
                }
            }
        val session = configured.session
        return when (
            val execution =
                executor.execute(endpoint, configured.limits) { progress ->
                    progress.advance(HostedQueryStage.PROJECT_ADMISSION)
                    when (val admission = session.admit(project, selection.root, candidate, policy)) {
                        is ExistingProjectAdmission.Admitted ->
                            admission.project.prepareHostedQuery(selection, progress, checkpoint)
                        is ExistingProjectAdmission.Rejected ->
                            HostedReadPreparation.Rejected(HostedQueryFailure.ProjectAdmission(admission.failure))
                    }
                }
        ) {
            is HostedExecution.Rejected -> HostedQueryResult.Rejected(execution.failure, execution.stage)
            is HostedExecution.Completed ->
                when (val prepared = execution.value) {
                    is HostedReadPreparation.Prepared ->
                        HostedQueryResult.Published(
                            HostedQueryPublication(endpoint, prepared.epoch, prepared.model, prepared.evidence)
                        )
                    is HostedReadPreparation.Rejected -> HostedQueryResult.Rejected(prepared.failure, execution.stage)
                }
        }
    }

    /** Original owner only: completion proves all owned requests ended and listeners disconnected. */
    suspend fun detach(): HostedQueryRetirement {
        liveAuthorities.retire()
        executor.retire()
        executor.drain()
        Disposer.dispose(owner)
        return HostedQueryRetirement.RETIRED
    }

    override fun dispose() {
        liveAuthorities.retire()
        executor.retire()
        Disposer.dispose(owner)
    }
}

enum class HostedQueryRetirement {
    RETIRED
}

private sealed interface ConfiguredHostedSession {
    data class Ready(val limits: ReadLimits, val session: AdmittedIdeProjectSession) : ConfiguredHostedSession

    data class Rejected(val failure: HostedQueryFailure.Configuration) : ConfiguredHostedSession
}
