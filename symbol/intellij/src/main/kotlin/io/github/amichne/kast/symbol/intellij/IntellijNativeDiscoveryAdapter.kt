package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameRegistry
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation

internal sealed interface IntellijNativeDiscoveryResult {
    data class Discovered(
        val outcome: SymbolDiscoveryOutcome,
    ) : IntellijNativeDiscoveryResult

    data class Rejected(
        val reason: IntellijNativeDiscoveryRejection,
    ) : IntellijNativeDiscoveryResult

    data class ScopeRejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijNativeDiscoveryResult
}

internal class IntellijNativeDiscoveryAdapter(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
) {
    /**
     * Proof transition:
     * Project + SymbolDiscoveryRequest + WorkspaceSearchScopeModelCompilation to
     * IntellijNativeDiscoveryResult.
     *
     * Establishes a write-priority cancellable IntelliJ read whose Choose-by-Name provider work can
     * begin only after KIP-012 compiles exact model ownership into a generation-bound native scope.
     * [IntellijSearchScopeFailure], [IntellijNativeDiscoveryRejection], and
     * [io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification] are the closed expected
     * failure and partial-coverage states. Cancellation propagates through [readAction]. The live
     * project, providers, PSI, files, and scope remain inside the restarted request-local read.
     */
    suspend fun discover(
        project: Project,
        request: SymbolDiscoveryRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijNativeDiscoveryResult = readAction {
        when (
            val scoped = scopeQuery.execute(
                project = project,
                request = request.scope,
                modelCompilation = modelCompilation,
            ) { compiledScope ->
                IntellijNativeDiscoveryQuery(
                    environmentState = { project.discoveryEnvironmentState() },
                    cancellationCheck = ProgressManager::checkCanceled,
                ).discover(
                    compiledScope = compiledScope,
                    request = request,
                    contributors = request.kind.nativeContributors(),
                )
            }
        ) {
            is IntellijScopedQueryResult.Completed -> when (val execution = scoped.value) {
                is IntellijNativeDiscoveryExecution.Produced ->
                    IntellijNativeDiscoveryResult.Discovered(execution.outcome)
                is IntellijNativeDiscoveryExecution.Rejected ->
                    IntellijNativeDiscoveryResult.Rejected(execution.reason)
            }
            is IntellijScopedQueryResult.Rejected ->
                IntellijNativeDiscoveryResult.ScopeRejected(scoped.failures)
        }
    }
}

/**
 * Proof transition: Project to IntellijDiscoveryEnvironmentState.
 *
 * Establishes the closed request-local READY, DUMB, or DISPOSED state before and during native
 * discovery. The live [Project] may be extracted only inside the restartable IntelliJ read.
 */
private fun Project.discoveryEnvironmentState(): IntellijDiscoveryEnvironmentState = when {
    isDisposed -> IntellijDiscoveryEnvironmentState.DISPOSED
    DumbService.getInstance(this).isDumb -> IntellijDiscoveryEnvironmentState.DUMB
    else -> IntellijDiscoveryEnvironmentState.READY
}

private fun SymbolDiscoveryKind.nativeContributors(): List<ChooseByNameContributor> = when (this) {
    SymbolDiscoveryKind.FILE -> ChooseByNameContributor.FILE_EP_NAME.extensionList
    SymbolDiscoveryKind.CLASS -> ChooseByNameContributor.CLASS_EP_NAME.extensionList
    SymbolDiscoveryKind.SYMBOL -> ChooseByNameRegistry.getInstance().symbolModelContributors
}
