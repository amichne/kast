package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameRegistry
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

internal sealed interface IntellijNativeDiscoveryResult {
    data class Discovered(val outcome: SymbolDiscoveryOutcome) : IntellijNativeDiscoveryResult

    data class Rejected(val reason: IntellijNativeDiscoveryRejection) : IntellijNativeDiscoveryResult

    data class ScopeRejected(val failures: Set<IntellijSearchScopeFailure>) : IntellijNativeDiscoveryResult
}

internal class IntellijNativeDiscoveryAdapter(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
) {
    /**
     * Proof transition: Project + SymbolDiscoveryRequest + WorkspaceSearchScopeModelCompilation to
     * IntellijNativeDiscoveryResult.
     *
     * Establishes a write-priority cancellable IntelliJ read whose Choose-by-Name provider work can begin only after
     * KIP-012 compiles exact model ownership into a authority-bound native scope. File contributors remain scoped
     * discovery providers; class and symbol contributors are limited to Kotlin declarations that can refine into the
     * product's K2 exact selector. [IntellijSearchScopeFailure], [IntellijNativeDiscoveryRejection], and
     * [io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification] are the closed expected failure and
     * partial-coverage states. Cancellation propagates through [readAction]. The live project, providers, PSI, files,
     * and scope remain inside the restarted request-local read.
     */
    suspend fun discover(
        project: Project,
        request: SymbolDiscoveryRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijNativeDiscoveryResult = readAction {
        when (
            val scoped =
                scopeQuery.execute(
                    project = project,
                    request = request.scope,
                    modelCompilation = modelCompilation,
                ) { compiledScope ->
                    when (val target = request.target) {
                        is SymbolDiscoveryTarget.All,
                        is SymbolDiscoveryTarget.Name ->
                            IntellijNativeDiscoveryQuery(
                                    environmentState = { project.discoveryEnvironmentState() },
                                    cancellationCheck = ProgressManager::checkCanceled,
                                    observation = observation,
                                    limits = limits,
                                )
                                .discoverNative(project, compiledScope, request)
                        is SymbolDiscoveryTarget.Location,
                        is SymbolDiscoveryTarget.Text ->
                            IntellijSupplementalDiscoveryQuery(
                                    project = project,
                                    limits = limits,
                                    environmentState = { project.discoveryEnvironmentState() },
                                )
                                .discover(compiledScope, request)
                    }
                }
        ) {
            is IntellijScopedQueryResult.Completed ->
                when (val execution = scoped.value) {
                    is IntellijNativeDiscoveryExecution.Produced ->
                        IntellijNativeDiscoveryResult.Discovered(execution.outcome)
                    is IntellijNativeDiscoveryExecution.Rejected ->
                        IntellijNativeDiscoveryResult.Rejected(execution.reason)
                }
            is IntellijScopedQueryResult.Rejected -> IntellijNativeDiscoveryResult.ScopeRejected(scoped.failures)
        }
    }
}

/**
 * Proof transition: Project to IntellijDiscoveryEnvironmentState.
 *
 * Establishes the closed request-local READY, DUMB, or DISPOSED state before and during a native symbol read. The live
 * [Project] may be extracted only inside the restartable IntelliJ read.
 */
internal fun Project.discoveryEnvironmentState(): IntellijDiscoveryEnvironmentState =
    when {
        isDisposed -> IntellijDiscoveryEnvironmentState.DISPOSED
        DumbService.getInstance(this).isDumb -> IntellijDiscoveryEnvironmentState.DUMB
        else -> IntellijDiscoveryEnvironmentState.READY
    }

internal fun SymbolNameDiscoveryKind.nativeContributors(): List<ChooseByNameContributor> =
    when (this) {
        SymbolNameDiscoveryKind.FILE -> ChooseByNameContributor.FILE_EP_NAME.extensionList
        SymbolNameDiscoveryKind.CLASS -> ChooseByNameContributor.CLASS_EP_NAME.extensionList
        SymbolNameDiscoveryKind.SYMBOL -> ChooseByNameRegistry.getInstance().symbolModelContributors
    }

internal fun SymbolDiscoveryTarget.discoveryKind(): SymbolNameDiscoveryKind =
    when (this) {
        is SymbolDiscoveryTarget.All -> kind
        is SymbolDiscoveryTarget.Name -> kind
        is SymbolDiscoveryTarget.Location,
        is SymbolDiscoveryTarget.Text -> error("Supplemental discovery targets do not use Choose-by-Name contributors")
    }

internal fun SymbolNameDiscoveryKind.isAdmittedContributor(contributor: ChooseByNameContributor): Boolean =
    isAdmittedContributorName(contributor.javaClass.name)

internal fun SymbolNameDiscoveryKind.isAdmittedContributorName(className: String): Boolean =
    when (this) {
        SymbolNameDiscoveryKind.FILE -> true
        SymbolNameDiscoveryKind.CLASS -> className in setOf("org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor")
        SymbolNameDiscoveryKind.SYMBOL ->
            className in
                setOf(
                    "org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor",
                    "org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor",
                    "org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor",
                    "org.jetbrains.kotlin.idea.goto.KotlinGotoTypeAliasContributor",
                )
    }
