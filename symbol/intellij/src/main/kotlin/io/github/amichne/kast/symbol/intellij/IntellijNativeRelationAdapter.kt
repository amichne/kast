package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.symbol.contract.NativeRelationOutcome
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation

sealed interface IntellijNativeRelationResult {
    data class Read(
        val outcome: NativeRelationOutcome,
    ) : IntellijNativeRelationResult

    data class Rejected(
        val reason: IntellijNativeRelationRejection,
    ) : IntellijNativeRelationResult

    data class ScopeRejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijNativeRelationResult
}

class IntellijNativeRelationAdapter private constructor(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
    private val semanticPolicy: IntellijRelationSemanticPolicy,
) {
    constructor(semanticPolicy: IntellijRelationSemanticPolicy) :
        this(IntellijSearchScopeQueryAdapter(), semanticPolicy)

    /**
     * Proof transition:
     * Project + current CurrentWorkspaceReadLease + NativeRelationRequest +
     * WorkspaceSearchScopeModelCompilation to IntellijNativeRelationResult.
     *
     * Establishes current exact root/epoch admission, recompiles the selector's retained scope,
     * and executes one bounded relation family inside a restartable write-priority IntelliJ read.
     * Root/epoch, scope, subject identity, environment, and bounded coverage failures are
     * closed by [IntellijNativeRelationResult]. Platform cancellation propagates through
     * [readAction]. No live IntelliJ value survives the request.
     */
    suspend fun read(
        project: Project,
        currentLease: CurrentWorkspaceReadLease,
        request: NativeRelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijNativeRelationResult {
        when (
            val admission = admitExactSelectorLease(
                request.selector.lease,
                currentLease,
            )
        ) {
            IntellijExactSelectorLeaseAdmission.Admitted -> Unit
            is IntellijExactSelectorLeaseAdmission.Rejected ->
                return IntellijNativeRelationResult.Rejected(
                    when (admission.reason) {
                        IntellijExactSelectorRejection.WORKSPACE_ROOT_MISMATCH ->
                            IntellijNativeRelationRejection.WORKSPACE_ROOT_MISMATCH
                        IntellijExactSelectorRejection.CURRENT_EPOCH_MOVED ->
                            IntellijNativeRelationRejection.CURRENT_EPOCH_MOVED
                        else -> IntellijNativeRelationRejection.INTERNAL_INVARIANT
                    },
                )
        }
        return readAction {
            when (
                val scoped = scopeQuery.execute(
                    project = project,
                    request = SymbolSearchScopeRequest(
                        request.selector.lease,
                        request.selector.scope,
                    ),
                    modelCompilation = modelCompilation,
                ) { compiledScope ->
                    IntellijNativeRelationQuery(
                        search = IntellijPsiNativeRelationSearch(project, semanticPolicy),
                        projector = IntellijPsiRelationFactProjector,
                        environmentState = { project.discoveryEnvironmentState() },
                        cancellationCheck = ProgressManager::checkCanceled,
                    ).read(compiledScope, request)
                }
            ) {
                is IntellijScopedQueryResult.Completed -> when (val execution = scoped.value) {
                    is IntellijNativeRelationExecution.Produced ->
                        IntellijNativeRelationResult.Read(execution.outcome)
                    is IntellijNativeRelationExecution.Rejected ->
                        IntellijNativeRelationResult.Rejected(execution.reason)
                }
                is IntellijScopedQueryResult.Rejected ->
                    IntellijNativeRelationResult.ScopeRejected(scoped.failures)
            }
        }
    }
}
