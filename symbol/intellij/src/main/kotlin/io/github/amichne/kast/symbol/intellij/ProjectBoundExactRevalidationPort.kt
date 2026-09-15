package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilation
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilerPort
import io.github.amichne.kast.symbol.contract.ExactRevalidationLocator
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import java.util.concurrent.CancellationException

/** Original scope is compiled against the current model. No cached semantic result enters the compiler lookup. */
class ProjectBoundExactRevalidationPort(
    private val project: Project,
    private val model: WorkspaceSearchScopeModel,
    private val files: IntellijSemanticSourceFileAdmission,
    private val capture: IntellijExactRevalidationCapture,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: io.github.amichne.kast.kernel.ReadLimits = io.github.amichne.kast.kernel.ReadLimits.Default,
) : ExactRevalidationCompilerPort {
    override suspend fun confirm(
        locator: ExactRevalidationLocator,
        current: SemanticReadAuthority,
    ): ExactRevalidationCompilation = readAction {
        ProgressManager.checkCanceled()
        observation.count(IntellijReadCounter.REVALIDATION_LOOKUPS)
        val result =
            try {
                confirmInRead(locator, current)
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IndexNotReadyException) {
                rejected(ExactRevalidationRejection.COMPILER_UNAVAILABLE)
            } catch (_: java.io.IOException) {
                rejected(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
            } catch (failure: IllegalStateException) {
                observation.unexpected(
                    io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure.capture(
                        io.github.amichne.kast.workspace.intellij.read.IntellijReadStage.EXACT_REFINEMENT,
                        failure,
                        limits,
                    )
                )
                rejected(ExactRevalidationRejection.COMPILER_UNAVAILABLE)
            }
        if (result is ExactRevalidationCompilation.Rejected)
            observation.count(IntellijReadCounter.REVALIDATION_LOOKUPS_REJECTED)
        result
    }

    private fun confirmInRead(
        locator: ExactRevalidationLocator,
        current: SemanticReadAuthority,
    ): ExactRevalidationCompilation {
        if (project.isDisposed) return rejected(ExactRevalidationRejection.RETIRED)
        if (DumbService.isDumb(project)) return rejected(ExactRevalidationRejection.WORKSPACE_NOT_READY)
        val offset =
            when (val refined = SymbolDiscoverySourceOffset.parse(locator.evidence.range.startInclusive)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return rejected(ExactRevalidationRejection.DECLARATION_MISSING)
            }
        val key =
            IntellijExactDeclarationLookupKey(locator.evidence.file, offset, locator.evidence.name, locator.constraints)
        val scope =
            IntellijSearchScopeQueryAdapter(
                IntellijSearchScopeCompiler(locator.constraints) { path ->
                    files.admits(path, locator.constraints.sourceSets)
                }
            )
        return when (
            val result =
                scope.execute(
                    project,
                    SymbolSearchScopeRequest(current, locator.scope),
                    WorkspaceSearchScopeModelCompilation.Compiled(model),
                ) { compiled ->
                    confirmScoped(locator, compiled, key)
                }
        ) {
            is IntellijScopedQueryResult.Completed -> result.value
            is IntellijScopedQueryResult.Rejected -> rejected(ExactRevalidationRejection.SCOPE_REJECTED)
        }
    }

    private fun confirmScoped(
        locator: ExactRevalidationLocator,
        compiled: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): ExactRevalidationCompilation {
        val psi = IntellijPsiExactDeclarationLookup(project)
        val live =
            when (val found = psi.findLive(compiled, key)) {
                is IntellijLiveExactDeclarationLookupResult.Found -> found
                is IntellijLiveExactDeclarationLookupResult.Rejected ->
                    return rejected(found.reason.revalidationFailure())
            }
        when (val checked = capture.check(locator, live.declaration.containingFile)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return rejected(checked.failure)
        }
        return when (val found = IntellijKotlinCompilerSymbolLookup(psi, observation, capture).find(compiled, key)) {
            is IntellijCompilerSymbolLookupResult.Found -> ExactRevalidationCompilation.Confirmed(found.evidence)
            is IntellijCompilerSymbolLookupResult.Rejected -> rejected(found.reason.revalidationFailure())
        }
    }
}

private fun rejected(reason: ExactRevalidationRejection) = ExactRevalidationCompilation.Rejected(reason)

private fun IntellijExactDeclarationLookupRejection.revalidationFailure(): ExactRevalidationRejection =
    when (this) {
        IntellijExactDeclarationLookupRejection.STALE_LOCATION -> ExactRevalidationRejection.DECLARATION_MISSING
        IntellijExactDeclarationLookupRejection.OUTSIDE_SCOPE -> ExactRevalidationRejection.SCOPE_REJECTED
        IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION -> ExactRevalidationRejection.AMBIGUOUS
        IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION ->
            ExactRevalidationRejection.UNSUPPORTED_DECLARATION
    }

private fun IntellijSymbolSelectorRejection.revalidationFailure(): ExactRevalidationRejection =
    when (this) {
        IntellijSymbolSelectorRejection.WORKSPACE_ROOT_MISMATCH -> ExactRevalidationRejection.WORKSPACE_MISMATCH
        IntellijSymbolSelectorRejection.GENERATION_MOVED -> ExactRevalidationRejection.BASIS_MOVED
        IntellijSymbolSelectorRejection.SCOPE_REJECTED,
        IntellijSymbolSelectorRejection.OUTSIDE_SCOPE -> ExactRevalidationRejection.SCOPE_REJECTED
        IntellijSymbolSelectorRejection.DUMB_MODE -> ExactRevalidationRejection.WORKSPACE_NOT_READY
        IntellijSymbolSelectorRejection.PROJECT_DISPOSED -> ExactRevalidationRejection.RETIRED
        IntellijSymbolSelectorRejection.STALE_LOCATION -> ExactRevalidationRejection.DECLARATION_MISSING
        IntellijSymbolSelectorRejection.AMBIGUOUS_DECLARATION -> ExactRevalidationRejection.AMBIGUOUS
        IntellijSymbolSelectorRejection.UNSUPPORTED_DECLARATION -> ExactRevalidationRejection.UNSUPPORTED_DECLARATION
        IntellijSymbolSelectorRejection.COMPILER_EVIDENCE_MISMATCH,
        IntellijSymbolSelectorRejection.DECLARATION_MOVED_OR_CHANGED ->
            ExactRevalidationRejection.COMPILER_IDENTITY_CHANGED
        IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE,
        IntellijSymbolSelectorRejection.NATIVE_FAILURE,
        IntellijSymbolSelectorRejection.INTERNAL_INVARIANT -> ExactRevalidationRejection.COMPILER_UNAVAILABLE
    }
