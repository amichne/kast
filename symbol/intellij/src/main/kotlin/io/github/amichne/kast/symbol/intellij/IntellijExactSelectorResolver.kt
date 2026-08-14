package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.RevalidatedExactDeclaration
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import java.util.concurrent.CancellationException

internal enum class IntellijExactSelectorRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    DUMB_MODE,
    PROJECT_DISPOSED,
    STALE_LOCATION,
    OUTSIDE_SCOPE,
    AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION,
    NATIVE_EVIDENCE_MISMATCH,
    DECLARATION_MOVED_OR_CHANGED,
    NATIVE_FAILURE,
    INTERNAL_INVARIANT,
}

internal sealed interface IntellijExactSelectorResolution {
    data class Resolved(
        val selector: ExactDeclarationSelector,
    ) : IntellijExactSelectorResolution

    data class Rejected(
        val reason: IntellijExactSelectorRejection,
    ) : IntellijExactSelectorResolution

    data class ScopeRejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijExactSelectorResolution
}

internal sealed interface IntellijExactSelectorRevalidation {
    data class Revalidated(
        val proof: RevalidatedExactDeclaration,
    ) : IntellijExactSelectorRevalidation

    data class Rejected(
        val reason: IntellijExactSelectorRejection,
    ) : IntellijExactSelectorRevalidation

    data class ScopeRejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijExactSelectorRevalidation
}

internal sealed interface IntellijExactSelectorLeaseAdmission {
    data object Admitted : IntellijExactSelectorLeaseAdmission

    data class Rejected(
        val reason: IntellijExactSelectorRejection,
    ) : IntellijExactSelectorLeaseAdmission
}

private sealed interface IntellijExactSelectorEnvironmentAdmission {
    data object Ready : IntellijExactSelectorEnvironmentAdmission

    data class Rejected(
        val reason: IntellijExactSelectorRejection,
    ) : IntellijExactSelectorEnvironmentAdmission
}

internal class IntellijExactSelectorQuery(
    private val lookup: IntellijExactDeclarationLookup,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
) {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + SymbolDiscoverySelection to
     * IntellijExactSelectorResolution.
     *
     * Establishes that one batch-owned declaration selection resolved through the same exact
     * generation and native scope before an opaque selector was issued. Expected failures are the
     * closed [IntellijExactSelectorRejection] states. Platform cancellation propagates. Live PSI,
     * files, and scopes remain inside [lookup].
     */
    fun resolve(
        compiledScope: CompiledIntellijSearchScope,
        selection: SymbolDiscoverySelection,
    ): IntellijExactSelectorResolution {
        if (
            compiledScope.lease != selection.lease ||
            compiledScope.scope != selection.scope
        ) {
            return IntellijExactSelectorResolution.Rejected(
                IntellijExactSelectorRejection.INTERNAL_INVARIANT,
            )
        }
        when (val admission = admitEnvironment()) {
            IntellijExactSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijExactSelectorEnvironmentAdmission.Rejected ->
                return IntellijExactSelectorResolution.Rejected(admission.reason)
        }
        val lookupResult = try {
            lookup.find(compiledScope, selection.lookupKey())
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            return IntellijExactSelectorResolution.Rejected(
                IntellijExactSelectorRejection.DUMB_MODE,
            )
        } catch (_: RuntimeException) {
            return IntellijExactSelectorResolution.Rejected(
                IntellijExactSelectorRejection.NATIVE_FAILURE,
            )
        }
        val evidence = when (lookupResult) {
            is IntellijExactDeclarationLookupResult.Found -> lookupResult.evidence
            is IntellijExactDeclarationLookupResult.Rejected ->
                return IntellijExactSelectorResolution.Rejected(
                    lookupResult.reason.toPublicRejection(),
                )
        }
        when (val admission = admitEnvironment()) {
            IntellijExactSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijExactSelectorEnvironmentAdmission.Rejected ->
                return IntellijExactSelectorResolution.Rejected(admission.reason)
        }
        return when (val issued = ExactDeclarationSelector.issue(selection, evidence)) {
            is Refinement.Refined -> IntellijExactSelectorResolution.Resolved(issued.value)
            is Refinement.Rejected ->
                IntellijExactSelectorResolution.Rejected(
                    IntellijExactSelectorRejection.NATIVE_EVIDENCE_MISMATCH,
                )
        }
    }

    /**
     * Proof transition:
     * CompiledIntellijSearchScope + ExactDeclarationSelector to
     * IntellijExactSelectorRevalidation.
     *
     * Establishes that a selector-only lookup resolved identical declaration evidence through the
     * selector's original generation and native scope. Expected failures are the closed
     * [IntellijExactSelectorRejection] states. Platform cancellation propagates. Live PSI, files,
     * and scopes remain inside [lookup].
     */
    fun revalidate(
        compiledScope: CompiledIntellijSearchScope,
        selector: ExactDeclarationSelector,
    ): IntellijExactSelectorRevalidation {
        if (
            compiledScope.lease != selector.lease ||
            compiledScope.scope != selector.scope
        ) {
            return IntellijExactSelectorRevalidation.Rejected(
                IntellijExactSelectorRejection.INTERNAL_INVARIANT,
            )
        }
        when (val admission = admitEnvironment()) {
            IntellijExactSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijExactSelectorEnvironmentAdmission.Rejected ->
                return IntellijExactSelectorRevalidation.Rejected(admission.reason)
        }
        val lookupResult = try {
            lookup.find(compiledScope, selector.lookupKey())
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            return IntellijExactSelectorRevalidation.Rejected(
                IntellijExactSelectorRejection.DUMB_MODE,
            )
        } catch (_: RuntimeException) {
            return IntellijExactSelectorRevalidation.Rejected(
                IntellijExactSelectorRejection.NATIVE_FAILURE,
            )
        }
        val evidence = when (lookupResult) {
            is IntellijExactDeclarationLookupResult.Found -> lookupResult.evidence
            is IntellijExactDeclarationLookupResult.Rejected ->
                return IntellijExactSelectorRevalidation.Rejected(
                    lookupResult.reason.toPublicRejection(),
                )
        }
        when (val admission = admitEnvironment()) {
            IntellijExactSelectorEnvironmentAdmission.Ready -> Unit
            is IntellijExactSelectorEnvironmentAdmission.Rejected ->
                return IntellijExactSelectorRevalidation.Rejected(admission.reason)
        }
        return when (val validated = RevalidatedExactDeclaration.validate(selector, evidence)) {
            is Refinement.Refined ->
                IntellijExactSelectorRevalidation.Revalidated(validated.value)
            is Refinement.Rejected ->
                IntellijExactSelectorRevalidation.Rejected(
                    IntellijExactSelectorRejection.DECLARATION_MOVED_OR_CHANGED,
                )
        }
    }

    /**
     * Proof transition:
     * live IntelliJ environment observation to IntellijExactSelectorEnvironmentAdmission.
     *
     * Establishes a ready project/index state or one closed dumb/disposed rejection before or after
     * native lookup. Platform cancellation propagates from [cancellationCheck].
     */
    private fun admitEnvironment(): IntellijExactSelectorEnvironmentAdmission {
        cancellationCheck()
        return when (environmentState()) {
            IntellijDiscoveryEnvironmentState.READY ->
                IntellijExactSelectorEnvironmentAdmission.Ready
            IntellijDiscoveryEnvironmentState.DUMB ->
                IntellijExactSelectorEnvironmentAdmission.Rejected(
                    IntellijExactSelectorRejection.DUMB_MODE,
                )
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                IntellijExactSelectorEnvironmentAdmission.Rejected(
                    IntellijExactSelectorRejection.PROJECT_DISPOSED,
                )
        }
    }
}

internal class IntellijExactSelectorResolver(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
) {
    /**
     * Proof transition:
     * Project + current SemanticReadLease + SymbolDiscoverySelection +
     * WorkspaceSearchScopeModelCompilation to IntellijExactSelectorResolution.
     *
     * Establishes that the selected root and generation are still current, then compiles the
     * original discovery scope before one restartable, write-priority IntelliJ read issues an exact
     * selector. Root/generation, scope, environment, and native lookup failures are closed by
     * [IntellijExactSelectorResolution]. Platform cancellation propagates through [readAction].
     */
    suspend fun resolve(
        project: Project,
        currentLease: SemanticReadLease,
        selection: SymbolDiscoverySelection,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijExactSelectorResolution {
        when (val admission = admitExactSelectorLease(selection.lease, currentLease)) {
            IntellijExactSelectorLeaseAdmission.Admitted -> Unit
            is IntellijExactSelectorLeaseAdmission.Rejected ->
                return IntellijExactSelectorResolution.Rejected(admission.reason)
        }
        return readAction {
            val query = project.query()
            exactSelectorResolutionFromScoped(
                scopeQuery.execute(
                    project = project,
                    request = SymbolSearchScopeRequest(selection.lease, selection.scope),
                    modelCompilation = modelCompilation,
                ) { compiledScope ->
                    query.resolve(compiledScope, selection)
                },
            )
        }
    }

    /**
     * Proof transition:
     * Project + current SemanticReadLease + ExactDeclarationSelector +
     * WorkspaceSearchScopeModelCompilation to IntellijExactSelectorRevalidation.
     *
     * Establishes current root/generation admission and identical native declaration evidence under
     * the selector's original compiled scope. Root/generation, scope, environment, movement, and
     * lookup failures are closed by [IntellijExactSelectorRevalidation]. Platform cancellation
     * propagates through [readAction].
     */
    suspend fun revalidate(
        project: Project,
        currentLease: SemanticReadLease,
        selector: ExactDeclarationSelector,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijExactSelectorRevalidation {
        when (val admission = admitExactSelectorLease(selector.lease, currentLease)) {
            IntellijExactSelectorLeaseAdmission.Admitted -> Unit
            is IntellijExactSelectorLeaseAdmission.Rejected ->
                return IntellijExactSelectorRevalidation.Rejected(admission.reason)
        }
        return readAction {
            val query = project.query()
            exactSelectorRevalidationFromScoped(
                scopeQuery.execute(
                    project = project,
                    request = SymbolSearchScopeRequest(selector.lease, selector.scope),
                    modelCompilation = modelCompilation,
                ) { compiledScope ->
                    query.revalidate(compiledScope, selector)
                },
            )
        }
    }

    private fun Project.query(): IntellijExactSelectorQuery =
        IntellijExactSelectorQuery(
            lookup = IntellijPsiExactDeclarationLookup(this),
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
 * Proof transition:
 * expected SemanticReadLease + current SemanticReadLease to
 * IntellijExactSelectorLeaseAdmission.
 *
 * [IntellijExactSelectorLeaseAdmission.Admitted] establishes exact root/generation equality;
 * [IntellijExactSelectorLeaseAdmission.Rejected] distinguishes root drift from generation
 * movement. Both inputs are already strong detached lease values, and no raw extraction crosses
 * this admission boundary.
 */
internal fun admitExactSelectorLease(
    expected: SemanticReadLease,
    current: SemanticReadLease,
): IntellijExactSelectorLeaseAdmission = when {
    expected.workspaceRoot != current.workspaceRoot ->
        IntellijExactSelectorLeaseAdmission.Rejected(
            IntellijExactSelectorRejection.WORKSPACE_ROOT_MISMATCH,
        )
    expected.generation != current.generation ->
        IntellijExactSelectorLeaseAdmission.Rejected(
            IntellijExactSelectorRejection.GENERATION_MOVED,
        )
    else -> IntellijExactSelectorLeaseAdmission.Admitted
}

internal fun exactSelectorResolutionFromScoped(
    scoped: IntellijScopedQueryResult<IntellijExactSelectorResolution>,
): IntellijExactSelectorResolution = when (scoped) {
    is IntellijScopedQueryResult.Completed -> scoped.value
    is IntellijScopedQueryResult.Rejected ->
        IntellijExactSelectorResolution.ScopeRejected(scoped.failures)
}

internal fun exactSelectorRevalidationFromScoped(
    scoped: IntellijScopedQueryResult<IntellijExactSelectorRevalidation>,
): IntellijExactSelectorRevalidation = when (scoped) {
    is IntellijScopedQueryResult.Completed -> scoped.value
    is IntellijScopedQueryResult.Rejected ->
        IntellijExactSelectorRevalidation.ScopeRejected(scoped.failures)
}
