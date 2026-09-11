package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.contract.AddDeclarationSourceText
import io.github.amichne.kast.change.contract.InstalledAddDeclarationIntentCompilation
import io.github.amichne.kast.change.contract.InstalledAddDeclarationIntentFailure
import io.github.amichne.kast.change.contract.LiveAddDeclarationCompilation
import io.github.amichne.kast.change.contract.LiveAddDeclarationCompilationFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Reuses the admitted declaration compiler and physical observation in the original project. */
object HostedLiveAddDeclarationCompiler {
    suspend fun compile(
        project: Project,
        context: HostedSemanticReadContext,
        selector: SymbolSelector,
        rawDeclaration: String,
    ): LiveAddDeclarationCompilation {
        if (
            selector.lease !== context.authority ||
                context.validation.validate(context.authority) != SemanticReadValidation.CURRENT
        ) {
            return rejected(LiveAddDeclarationCompilationFailure.AUTHORITY_MOVED)
        }
        val source =
            when (val file = selector.file) {
                is SymbolDiscoveryFileIdentity.Workspace -> file
                is SymbolDiscoveryFileIdentity.External ->
                    return rejected(LiveAddDeclarationCompilationFailure.TARGET_UNAVAILABLE)
            }
        val declaration =
            when (val result = AddDeclarationSourceText.parse(rawDeclaration)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return rejected(LiveAddDeclarationCompilationFailure.DECLARATION_REJECTED)
            }
        val compiled = compileDeclaredIntent(project, selector, declaration)
        val intent =
            when (compiled) {
                is InstalledAddDeclarationIntentCompilation.Compiled -> compiled.intent
                is InstalledAddDeclarationIntentCompilation.Rejected -> return rejected(compiled.failure.liveFailure())
            }
        val observed =
            when (val result = IntellijChangeSourceAdapter(project).observe(source, context.limits)) {
                is SourceObservationResult.Observed ->
                    when (val value = result.source) {
                        is ObservedMutationSource -> value
                        else -> return rejected(LiveAddDeclarationCompilationFailure.TARGET_UNAVAILABLE)
                    }
                is SourceObservationResult.Rejected ->
                    return rejected(LiveAddDeclarationCompilationFailure.TARGET_UNAVAILABLE)
            }
        if (observed.access != SourceWriteAccess.Writable)
            return rejected(LiveAddDeclarationCompilationFailure.TARGET_READ_ONLY)
        if (context.validation.validate(context.authority) != SemanticReadValidation.CURRENT) {
            return rejected(LiveAddDeclarationCompilationFailure.AUTHORITY_MOVED)
        }
        return LiveAddDeclarationCompilation.Compiled(intent, observed.content)
    }

    private fun compileDeclaredIntent(
        project: Project,
        selector: SymbolSelector,
        declaration: AddDeclarationSourceText,
    ): InstalledAddDeclarationIntentCompilation =
        try {
            ProgressManager.checkCanceled()
            if (project.isDisposed || DumbService.getInstance(project).isDumb) {
                return InstalledAddDeclarationIntentCompilation.Rejected(
                    InstalledAddDeclarationIntentFailure.PROJECT_UNAVAILABLE
                )
            }
            ReadAction.nonBlocking<InstalledAddDeclarationIntentCompilation> {
                    compileIntentRead(project, selector, declaration)
                }
                .inSmartMode(project)
                .executeSynchronously()
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            return InstalledAddDeclarationIntentCompilation.Rejected(
                InstalledAddDeclarationIntentFailure.COMPILER_IDENTITY_UNAVAILABLE
            )
        }

    private fun rejected(failure: LiveAddDeclarationCompilationFailure) =
        LiveAddDeclarationCompilation.Rejected(failure)

    private fun InstalledAddDeclarationIntentFailure.liveFailure(): LiveAddDeclarationCompilationFailure =
        when (this) {
            InstalledAddDeclarationIntentFailure.PROJECT_UNAVAILABLE ->
                LiveAddDeclarationCompilationFailure.PROJECT_UNAVAILABLE
            InstalledAddDeclarationIntentFailure.GENERATION_MOVED ->
                LiveAddDeclarationCompilationFailure.AUTHORITY_MOVED
            InstalledAddDeclarationIntentFailure.TARGET_UNAVAILABLE,
            InstalledAddDeclarationIntentFailure.TARGET_NOT_KOTLIN,
            InstalledAddDeclarationIntentFailure.TARGET_MOVED -> LiveAddDeclarationCompilationFailure.TARGET_UNAVAILABLE
            InstalledAddDeclarationIntentFailure.DECLARATION_REJECTED ->
                LiveAddDeclarationCompilationFailure.DECLARATION_REJECTED
            InstalledAddDeclarationIntentFailure.COMPILER_IDENTITY_UNAVAILABLE ->
                LiveAddDeclarationCompilationFailure.COMPILER_UNAVAILABLE
        }
}
