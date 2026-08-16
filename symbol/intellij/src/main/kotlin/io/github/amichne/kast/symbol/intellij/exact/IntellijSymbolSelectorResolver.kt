package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.RevalidatedSymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import java.util.concurrent.CancellationException

internal enum class IntellijSymbolSelectorRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    SCOPE_REJECTED,
    DUMB_MODE,
    PROJECT_DISPOSED,
    STALE_LOCATION,
    OUTSIDE_SCOPE,
    AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION,
    COMPILER_IDENTITY_UNAVAILABLE,
    COMPILER_EVIDENCE_MISMATCH,
    DECLARATION_MOVED_OR_CHANGED,
    NATIVE_FAILURE,
    INTERNAL_INVARIANT,
}

internal sealed interface IntellijSymbolSelectorLeaseAdmission {
    data object Admitted : IntellijSymbolSelectorLeaseAdmission

    data class Rejected(
        val reason: IntellijSymbolSelectorRejection,
    ) : IntellijSymbolSelectorLeaseAdmission
}

internal sealed interface IntellijSymbolSelectorResolution {
    data class Resolved(
        val selector: SymbolSelector,
    ) : IntellijSymbolSelectorResolution

    data class Rejected(
        val reason: IntellijSymbolSelectorRejection,
    ) : IntellijSymbolSelectorResolution
}

internal sealed interface IntellijSymbolDescriptionResolution {
    data class Described(
        val description: SymbolDescription,
    ) : IntellijSymbolDescriptionResolution

    data class Rejected(
        val reason: IntellijSymbolSelectorRejection,
    ) : IntellijSymbolDescriptionResolution
}

private sealed interface IntellijSymbolSelectorEnvironmentAdmission {
    data object Ready : IntellijSymbolSelectorEnvironmentAdmission

    data class Rejected(
        val reason: IntellijSymbolSelectorRejection,
    ) : IntellijSymbolSelectorEnvironmentAdmission
}

internal class IntellijSymbolSelectorQuery(
    private val lookup: IntellijCompilerSymbolLookup,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
) {
    /**
     * Proof transition: `(CompiledIntellijSearchScope, SymbolDiscoverySelection) ->
     * IntellijSymbolSelectorResolution`.
     *
     * Establishes same-lease/scope admission, a request-local K2 compiler lookup, and issuance of
     * one exact [SymbolSelector]. [IntellijSymbolSelectorRejection] is the closed expected failure.
     * Platform cancellation propagates; live project, scope, PSI, VFS, and K2 values remain in the
     * lookup call.
     */
    fun resolve(
        compiledScope: CompiledIntellijSearchScope,
        selection: SymbolDiscoverySelection,
    ): IntellijSymbolSelectorResolution {
        if (compiledScope.lease != selection.lease || compiledScope.scope != selection.scope) {
            return rejectedResolution(IntellijSymbolSelectorRejection.INTERNAL_INVARIANT)
        }
        when (val admission = admitEnvironment()) {
            IntellijSymbolSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijSymbolSelectorEnvironmentAdmission.Rejected ->
                return rejectedResolution(admission.reason)
        }
        val evidence = when (val result = find(compiledScope, selection.lookupKey())) {
            is IntellijCompilerSymbolLookupResult.Found -> result.evidence
            is IntellijCompilerSymbolLookupResult.Rejected ->
                return rejectedResolution(result.reason)
        }
        when (val admission = admitEnvironment()) {
            IntellijSymbolSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijSymbolSelectorEnvironmentAdmission.Rejected ->
                return rejectedResolution(admission.reason)
        }
        return when (val selector = SymbolSelector.issue(selection, evidence)) {
            is Refinement.Refined -> IntellijSymbolSelectorResolution.Resolved(selector.value)
            is Refinement.Rejected ->
                rejectedResolution(IntellijSymbolSelectorRejection.COMPILER_EVIDENCE_MISMATCH)
        }
    }

    /**
     * Proof transition: `(CompiledIntellijSearchScope, SymbolSelector) ->
     * IntellijSymbolDescriptionResolution`.
     *
     * Establishes identical current K2 evidence under the selector's retained lease and scope,
     * then projects a detached description. [IntellijSymbolSelectorRejection] is the closed
     * expected failure. Platform cancellation propagates; no live IntelliJ or compiler value
     * crosses this call.
     */
    fun describe(
        compiledScope: CompiledIntellijSearchScope,
        selector: SymbolSelector,
    ): IntellijSymbolDescriptionResolution {
        if (compiledScope.lease != selector.lease || compiledScope.scope != selector.scope) {
            return rejectedDescription(IntellijSymbolSelectorRejection.INTERNAL_INVARIANT)
        }
        when (val admission = admitEnvironment()) {
            IntellijSymbolSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijSymbolSelectorEnvironmentAdmission.Rejected ->
                return rejectedDescription(admission.reason)
        }
        val evidence = when (val result = find(compiledScope, selector.lookupKey())) {
            is IntellijCompilerSymbolLookupResult.Found -> result.evidence
            is IntellijCompilerSymbolLookupResult.Rejected ->
                return rejectedDescription(result.reason)
        }
        when (val admission = admitEnvironment()) {
            IntellijSymbolSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijSymbolSelectorEnvironmentAdmission.Rejected ->
                return rejectedDescription(admission.reason)
        }
        return when (val proof = RevalidatedSymbolSelector.validate(selector, evidence)) {
            is Refinement.Refined -> IntellijSymbolDescriptionResolution.Described(
                SymbolDescription.from(proof.value.selector),
            )
            is Refinement.Rejected -> rejectedDescription(
                IntellijSymbolSelectorRejection.DECLARATION_MOVED_OR_CHANGED,
            )
        }
    }

    private fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijCompilerSymbolLookupResult = try {
        lookup.find(compiledScope, key)
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IndexNotReadyException) {
        IntellijCompilerSymbolLookupResult.Rejected(IntellijSymbolSelectorRejection.DUMB_MODE)
    } catch (_: RuntimeException) {
        IntellijCompilerSymbolLookupResult.Rejected(IntellijSymbolSelectorRejection.NATIVE_FAILURE)
    }

    /**
     * Proof transition: `live IntelliJ environment ->
     * IntellijSymbolSelectorEnvironmentAdmission`.
     *
     * Establishes a ready project/index state or a closed dumb/disposed rejection before and after
     * compiler work. Platform cancellation propagates from [cancellationCheck].
     */
    private fun admitEnvironment(): IntellijSymbolSelectorEnvironmentAdmission {
        cancellationCheck()
        return when (environmentState()) {
            IntellijDiscoveryEnvironmentState.READY ->
                IntellijSymbolSelectorEnvironmentAdmission.Ready
            IntellijDiscoveryEnvironmentState.DUMB ->
                IntellijSymbolSelectorEnvironmentAdmission.Rejected(
                    IntellijSymbolSelectorRejection.DUMB_MODE,
                )
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                IntellijSymbolSelectorEnvironmentAdmission.Rejected(
                    IntellijSymbolSelectorRejection.PROJECT_DISPOSED,
                )
        }
    }
}

internal class IntellijSymbolSelectorResolver(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
) {
    /**
     * Proof transition: `(Project, SemanticReadLease, SymbolResolutionRequest,
     * WorkspaceSearchScopeModelCompilation) -> IntellijSymbolSelectorResolution`.
     *
     * Establishes exact current-lease admission, recompiles the retained discovery scope, and
     * performs one restartable K2 selector read. [IntellijSymbolSelectorRejection] is the closed
     * expected failure. The live project, native scope, PSI, VFS, and compiler symbols remain
     * request-local.
     */
    suspend fun resolve(
        project: Project,
        currentLease: SemanticReadLease,
        request: SymbolResolutionRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijSymbolSelectorResolution {
        when (val admission = admitSymbolSelectorLease(request.selection.lease, currentLease)) {
            IntellijSymbolSelectorLeaseAdmission.Admitted -> Unit
            is IntellijSymbolSelectorLeaseAdmission.Rejected ->
                return rejectedResolution(admission.reason)
        }
        return readAction {
            when (
                val scoped = scopeQuery.execute(
                    project = project,
                    request = SymbolSearchScopeRequest(
                        request.selection.lease,
                        request.selection.scope,
                    ),
                    modelCompilation = modelCompilation,
                ) { compiled -> project.query().resolve(compiled, request.selection) }
            ) {
                is IntellijScopedQueryResult.Completed -> scoped.value
                is IntellijScopedQueryResult.Rejected ->
                    rejectedResolution(IntellijSymbolSelectorRejection.SCOPE_REJECTED)
            }
        }
    }

    /**
     * Proof transition: `(Project, SemanticReadLease, ExactSymbolRequest,
     * WorkspaceSearchScopeModelCompilation) -> IntellijSymbolDescriptionResolution`.
     *
     * Establishes exact current-lease admission, recompiles only the selector's retained scope,
     * and revalidates identical K2 evidence before detached description projection.
     * [IntellijSymbolSelectorRejection] is the closed expected failure. Live IntelliJ and compiler
     * values remain request-local.
     */
    suspend fun describe(
        project: Project,
        currentLease: SemanticReadLease,
        request: ExactSymbolRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijSymbolDescriptionResolution {
        when (val admission = admitSymbolSelectorLease(request.selector.lease, currentLease)) {
            IntellijSymbolSelectorLeaseAdmission.Admitted -> Unit
            is IntellijSymbolSelectorLeaseAdmission.Rejected ->
                return rejectedDescription(admission.reason)
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
                ) { compiled -> project.query().describe(compiled, request.selector) }
            ) {
                is IntellijScopedQueryResult.Completed -> scoped.value
                is IntellijScopedQueryResult.Rejected ->
                    rejectedDescription(IntellijSymbolSelectorRejection.SCOPE_REJECTED)
            }
        }
    }

    private fun Project.query(): IntellijSymbolSelectorQuery = IntellijSymbolSelectorQuery(
        lookup = IntellijKotlinCompilerSymbolLookup(IntellijPsiExactDeclarationLookup(this)),
        environmentState = {
            when {
                isDisposed -> IntellijDiscoveryEnvironmentState.DISPOSED
                DumbService.isDumb(this) -> IntellijDiscoveryEnvironmentState.DUMB
                else -> IntellijDiscoveryEnvironmentState.READY
            }
        },
        cancellationCheck = ProgressManager::checkCanceled,
    )
}

/**
 * Proof transition: `(SemanticReadLease, SemanticReadLease) ->
 * IntellijSymbolSelectorLeaseAdmission`.
 *
 * [IntellijSymbolSelectorLeaseAdmission.Admitted] establishes exact canonical root and generation
 * equality. [IntellijSymbolSelectorLeaseAdmission.Rejected] distinguishes root drift from
 * generation movement. No raw root or generation extraction crosses this adapter boundary.
 */
internal fun admitSymbolSelectorLease(
    expected: SemanticReadLease,
    current: SemanticReadLease,
): IntellijSymbolSelectorLeaseAdmission = when {
    expected.workspaceRoot != current.workspaceRoot ->
        IntellijSymbolSelectorLeaseAdmission.Rejected(
            IntellijSymbolSelectorRejection.WORKSPACE_ROOT_MISMATCH,
        )
    expected.generation != current.generation ->
        IntellijSymbolSelectorLeaseAdmission.Rejected(
            IntellijSymbolSelectorRejection.GENERATION_MOVED,
        )
    else -> IntellijSymbolSelectorLeaseAdmission.Admitted
}

private fun rejectedResolution(
    reason: IntellijSymbolSelectorRejection,
): IntellijSymbolSelectorResolution.Rejected =
    IntellijSymbolSelectorResolution.Rejected(reason)

private fun rejectedDescription(
    reason: IntellijSymbolSelectorRejection,
): IntellijSymbolDescriptionResolution.Rejected =
    IntellijSymbolDescriptionResolution.Rejected(reason)
