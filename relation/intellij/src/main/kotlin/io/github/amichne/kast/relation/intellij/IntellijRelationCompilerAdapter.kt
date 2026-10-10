package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionMode
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import io.github.amichne.kast.workspace.intellij.read.attempt
import io.github.amichne.kast.workspace.intellij.read.call
import io.github.amichne.kast.workspace.intellij.read.observeReadAction
import kotlinx.coroutines.CancellationException

internal sealed interface IntellijRelationLeaseAdmission {
    data object Admitted : IntellijRelationLeaseAdmission

    data class Rejected(val reason: RelationCompilerRejection) : IntellijRelationLeaseAdmission
}

/**
 * Proof transition: `(SemanticReadAuthority, SemanticReadAuthority) -> IntellijRelationLeaseAdmission`.
 *
 * Admitted proves exact canonical root and authority equality. Rejected preserves root mismatch or authority movement
 * as [RelationCompilerRejection]. Raw identity extraction stays at the workspace publication boundary.
 */
internal fun admitRelationLease(
    current: SemanticReadAuthority,
    requested: SemanticReadAuthority,
): IntellijRelationLeaseAdmission =
    when {
        current.workspaceRoot != requested.workspaceRoot ->
            IntellijRelationLeaseAdmission.Rejected(RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH)
        current != requested -> IntellijRelationLeaseAdmission.Rejected(RelationCompilerRejection.GENERATION_MOVED)
        else -> IntellijRelationLeaseAdmission.Admitted
    }

internal class IntellijRelationCompilerQuery(
    private val scopeCompiler: IntellijRelationScopeCompiler = IntellijRelationScopeCompiler(),
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
    private val summaries: io.github.amichne.kast.relation.contract.CallbackSummaryCachePreparationPort =
        io.github.amichne.kast.relation.contract.CallbackSummaryCachePreparationPort.Disabled,
    private val inventories: IntellijReferenceInventoryReuse = IntellijReferenceInventoryReuse.Disabled,
) {
    private fun prepareSummaries(
        request: RelationRequest,
        allowance: IntellijRelationAllowance,
    ): io.github.amichne.kast.relation.contract.CallbackSummaryCachePort =
        when (val remaining = allowance.remainingResources(request.budget.resources)) {
            is io.github.amichne.kast.kernel.Refinement.Refined ->
                summaries.prepare(
                    request,
                    remaining.value,
                    { allowance.remainingResources(request.budget.resources) },
                    allowance::chargePreparation,
                )
            is io.github.amichne.kast.kernel.Refinement.Rejected ->
                io.github.amichne.kast.relation.contract.CallbackSummaryCachePort.Disabled
        }

    private fun readNamedPartition(
        cache: io.github.amichne.kast.relation.contract.NamedRelationCachePort,
        request: RelationRequest,
        projection: IntellijK2RelationProjection,
        scope: CompiledRelationScope,
        allowance: IntellijRelationAllowance,
    ): io.github.amichne.kast.relation.contract.NamedRelationCacheLookup =
        when (
            val found =
                cache.find(request) { previous ->
                    readmitNamedRelationPartition(previous, request, projection, scope, allowance)
                }
        ) {
            io.github.amichne.kast.relation.contract.NamedRelationCacheLookup.Miss -> found
            is io.github.amichne.kast.relation.contract.NamedRelationCacheLookup.Found ->
                if (allowance.elapsedLimitReached(request.budget.resources))
                    io.github.amichne.kast.relation.contract.NamedRelationCacheLookup.Miss
                else {
                    cache.admitted(found.complete)
                    observation.count(IntellijReadCounter.NATIVE_RELATION_PAGES)
                    found
                }
        }

    private fun finish(
        compilation: RelationCompilation,
        cache: io.github.amichne.kast.relation.contract.NamedRelationCachePort,
    ): RelationCompilation {
        if (compilation is RelationCompilation.Complete) cache.retain(compilation)
        observation.count(IntellijReadCounter.NATIVE_RELATION_PAGES)
        return compilation
    }

    /**
     * Proof transition: `(Project, SemanticReadAuthority, RelationRequest, WorkspaceSearchScopeModelCompilation) ->
     * RelationCompilation`.
     *
     * A non-rejected result establishes current lease, exact retained scope, identical K2 subject, K2-confirmed one-hop
     * facts, request bounds, and terminal or resumable coverage. [RelationCompilerRejection] is the closed expected
     * failure. Live platform and compiler values remain within the restartable read action; platform cancellation
     * propagates.
     */
    suspend fun read(
        project: Project,
        currentLease: SemanticReadAuthority,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): RelationCompilation {
        when (val admission = admitRelationLease(currentLease, request.subject.lease)) {
            IntellijRelationLeaseAdmission.Admitted -> Unit
            is IntellijRelationLeaseAdmission.Rejected -> return RelationCompilation.Rejected(admission.reason)
        }
        if (project.isDisposed) {
            return RelationCompilation.Rejected(RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        }
        val allowance = IntellijRelationAllowance(System::nanoTime)
        return try {
            observation.observeReadAction(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ) { admission ->
                readAction {
                    admission.attempt {
                        observation.call(IntellijReadCall.RELATION_READ_ATTEMPT) {
                            admitThenPrepareRelation(
                                admit = { admitRead(project, request, modelCompilation, allowance) },
                                prepare = {
                                    observation.call(IntellijReadCall.CALLBACK_FACT_PREPARATION) {
                                        prepareSummaries(request, allowance)
                                    }
                                },
                                evaluate = { admitted, prepared ->
                                    evaluateRead(project, request, allowance, admitted, prepared)
                                },
                            )
                        }
                    }
                }
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            observation.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.RELATION, failure, limits))
            RelationCompilation.Rejected(RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        } catch (failure: LinkageError) {
            observation.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.RELATION, failure, limits))
            RelationCompilation.Rejected(RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        }
    }

    private class AdmittedRelationRead(
        val scope: CompiledRelationScope,
        val projection: IntellijK2RelationProjection,
        val subject: IntellijRelationSubjectLookup.Found,
    )

    private fun admitRead(
        project: Project,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        allowance: IntellijRelationAllowance,
    ): io.github.amichne.kast.kernel.Refinement<AdmittedRelationRead, RelationCompilerRejection> {
        val scopes =
            when (
                val admitted =
                    AdmittedRelationScopes.compile(request, observation) { selected, constraints ->
                        when (
                            val compilation =
                                scopeCompiler.compile(
                                    project,
                                    request,
                                    modelCompilation,
                                    selected,
                                    constraints,
                                    observation,
                                )
                        ) {
                            is IntellijRelationScopeCompilation.Compiled ->
                                io.github.amichne.kast.kernel.Refinement.Refined(compilation.scope)
                            is IntellijRelationScopeCompilation.Rejected ->
                                io.github.amichne.kast.kernel.Refinement.Rejected(
                                    RelationCompilerRejection.SCOPE_REJECTED
                                )
                        }
                    }
            ) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> admitted.value
                is io.github.amichne.kast.kernel.Refinement.Rejected -> return admitted
            }
        val projection =
            IntellijK2RelationProjection(
                project,
                request.subject.lease.workspaceRoot,
                observation,
            )
        val subject =
            when (
                val lookup =
                    observation.call(IntellijReadCall.RELATION_SUBJECT_RESTORE) {
                        projection.subject(scopes.subject, request.subject)
                    }
            ) {
                is IntellijRelationSubjectLookup.Found -> lookup
                is IntellijRelationSubjectLookup.Rejected ->
                    return io.github.amichne.kast.kernel.Refinement.Rejected(lookup.reason.compilerRejection())
            }

        return io.github.amichne.kast.kernel.Refinement.Refined(
            admittedRead(request, scopes.search, projection, subject, allowance)
        )
    }

    private fun admittedRead(
        request: RelationRequest,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
        subject: IntellijRelationSubjectLookup.Found,
        allowance: IntellijRelationAllowance,
    ): AdmittedRelationRead =
        AdmittedRelationRead(
            if (request.meaning == io.github.amichne.kast.relation.contract.RelationMeaning.Callees) scope
            else scope.prepareFileEnumeration(allowance, limits),
            projection,
            subject,
        )

    private fun evaluateRead(
        project: Project,
        request: RelationRequest,
        allowance: IntellijRelationAllowance,
        admitted: AdmittedRelationRead,
        preparedSummaries: io.github.amichne.kast.relation.contract.CallbackSummaryCachePort,
    ): RelationCompilation {
        val scope = admitted.scope
        val projection = admitted.projection
        val subject = admitted.subject
        val namedCache = preparedSummaries.namedRelations
        when (val reused = readNamedPartition(namedCache, request, projection, scope, allowance)) {
            io.github.amichne.kast.relation.contract.NamedRelationCacheLookup.Miss -> Unit
            is io.github.amichne.kast.relation.contract.NamedRelationCacheLookup.Found -> return reused.complete
        }
        val collector =
            IntellijRelationCollector(
                request,
                observation = observation,
                limits = limits,
                allowance = allowance,
            )
        val termination =
            IntellijK2RelationSearch(
                    project,
                    scope,
                    projection,
                    observation = observation,
                    limits = limits,
                    summaries = preparedSummaries,
                    inventories = inventories,
                )
                .read(request, subject.plan(request), collector)
        return finish(collector.finish(termination), namedCache)
    }
}

/** Public native K2 boundary for exact one-hop relation compilation. */
class IntellijRelationCompilerAdapter private constructor(private val query: IntellijRelationCompilerQuery) {
    constructor() : this(IntellijRelationCompilerQuery())

    /**
     * Proof transition: `(Project, SemanticReadAuthority, RelationRequest, WorkspaceSearchScopeModelCompilation) ->
     * RelationCompilation`.
     *
     * Complete or qualified output carries only detached exact facts and coverage from the current request.
     * [RelationCompilerRejection] is the closed expected failure. Project, PSI, VFS, native searches, and K2 session
     * values never cross this method boundary.
     */
    suspend fun read(
        project: Project,
        currentLease: SemanticReadAuthority,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): RelationCompilation = query.read(project, currentLease, request, modelCompilation)
}

private fun IntellijRelationSubjectFailure.compilerRejection(): RelationCompilerRejection =
    when (this) {
        IntellijRelationSubjectFailure.STALE_SELECTOR -> RelationCompilerRejection.STALE_SELECTOR
        IntellijRelationSubjectFailure.OUTSIDE_SCOPE -> RelationCompilerRejection.OUTSIDE_SCOPE
        IntellijRelationSubjectFailure.AMBIGUOUS_SUBJECT -> RelationCompilerRejection.AMBIGUOUS_SUBJECT
        IntellijRelationSubjectFailure.UNSUPPORTED_SUBJECT -> RelationCompilerRejection.UNSUPPORTED_SUBJECT
        IntellijRelationSubjectFailure.COMPILER_IDENTITY_UNAVAILABLE ->
            RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE
    }
