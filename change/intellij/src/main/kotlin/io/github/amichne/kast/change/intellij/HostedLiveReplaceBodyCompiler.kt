package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.contract.ExistingBodySourceText
import io.github.amichne.kast.change.contract.InstalledReplaceBodyIntent
import io.github.amichne.kast.change.contract.LiveReplaceBodyCompilation
import io.github.amichne.kast.change.contract.LiveReplaceBodyCompilationFailure
import io.github.amichne.kast.change.contract.ReplaceBodyPreservation
import io.github.amichne.kast.change.contract.ReplaceBodySourceText
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.RevalidatedSymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import java.nio.file.Path
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Parses a complete block and binds it to one exact existing Kotlin function before planning. */
object HostedLiveReplaceBodyCompiler {
    @Suppress("LongMethod") // Bind one exact target, observed preimage, and whole replacement block in order.
    suspend fun compile(
        project: Project,
        context: HostedSemanticReadContext,
        selector: SymbolSelector,
        rawBody: String,
    ): LiveReplaceBodyCompilation {
        if (
            selector.lease !== context.authority ||
                context.validation.validate(context.authority) != SemanticReadValidation.CURRENT
        ) {
            return rejected(LiveReplaceBodyCompilationFailure.AUTHORITY_MOVED)
        }
        val source =
            selector.file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
        val proposed =
            when (val parsed = ReplaceBodySourceText.parse(rawBody)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return rejected(LiveReplaceBodyCompilationFailure.BODY_REJECTED)
            }
        val target = compileTarget(project, selector, proposed)
        val extracted =
            when (target) {
                is TargetCompilation.Compiled -> target
                is TargetCompilation.Rejected -> return rejected(target.failure)
            }
        val observed =
            when (val result = IntellijChangeSourceAdapter(project).observe(source, context.limits)) {
                is SourceObservationResult.Observed ->
                    result.source as? ObservedMutationSource
                        ?: return rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
                is SourceObservationResult.Rejected ->
                    return rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
            }
        if (observed.access != SourceWriteAccess.Writable)
            return rejected(LiveReplaceBodyCompilationFailure.TARGET_READ_ONLY)
        val preservation =
            when (
                val admitted =
                    ReplaceBodyPreservation.capture(
                        sourceText = extracted.sourceText,
                        targetRange = selector.range,
                        bodyRange = extracted.bodyRange,
                        originalBody = extracted.originalBody,
                        replacement = proposed,
                        content = observed.content,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(LiveReplaceBodyCompilationFailure.PREIMAGE_CHANGED)
            }
        if (context.validation.validate(context.authority) != SemanticReadValidation.CURRENT)
            return rejected(LiveReplaceBodyCompilationFailure.AUTHORITY_MOVED)
        return LiveReplaceBodyCompilation.Compiled(
            InstalledReplaceBodyIntent.fromCompiler(preservation),
            observed.content,
        )
    }

    private fun compileTarget(
        project: Project,
        selector: SymbolSelector,
        proposed: ReplaceBodySourceText,
    ): TargetCompilation =
        try {
            ProgressManager.checkCanceled()
            if (project.isDisposed || DumbService.getInstance(project).isDumb)
                return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.PROJECT_UNAVAILABLE)
            ReadAction.nonBlocking<TargetCompilation> { compileTargetRead(project, selector, proposed) }
                .inSmartMode(project)
                .executeSynchronously()
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.COMPILER_UNAVAILABLE)
        }

    @Suppress("CyclomaticComplexMethod") // Every compiler and PSI eligibility gate fails closed for this exact target.
    private fun compileTargetRead(
        project: Project,
        selector: SymbolSelector,
        proposed: ReplaceBodySourceText,
    ): TargetCompilation {
        if (selector.kind != CompilerSymbolKind.FUNCTION)
            return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.UNSUPPORTED_TARGET)
        val source =
            selector.file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
        val virtual =
            LocalFileSystem.getInstance().findFileByNioFile(Path.of(source.path.value))
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
        val file =
            PsiManager.getInstance(project).findFile(virtual) as? KtFile
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
        val matches =
            PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).filter { function ->
                function.textRange.startOffset == selector.range.startInclusive &&
                    function.textRange.endOffset == selector.range.endExclusive &&
                    function.name == selector.name.value
            }
        val function =
            matches.singleOrNull()
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
        val currentEvidence =
            function.compilerEvidence(source)
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.COMPILER_UNAVAILABLE)
        if (RevalidatedSymbolSelector.validate(selector, currentEvidence) is Refinement.Rejected)
            return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.TARGET_UNAVAILABLE)
        if (function.hasModifier(KtTokens.INLINE_KEYWORD) || !function.hasBlockBody())
            return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.UNSUPPORTED_TARGET)
        val body =
            function.bodyBlockExpression
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.UNSUPPORTED_TARGET)
        if (body.hasContractCall())
            return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.UNSUPPORTED_TARGET)
        if (!parsesAsOneCompleteBlock(project, proposed))
            return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.BODY_REJECTED)
        val range = ExactDeclarationTextRange.parse(body.textRange.startOffset, body.textRange.endOffset)
        val bodyRange =
            (range as? Refinement.Refined)?.value
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.COMPILER_UNAVAILABLE)
        val oldBody = ExistingBodySourceText.fromCompiler(body.text)
        val original =
            (oldBody as? Refinement.Refined)?.value
                ?: return TargetCompilation.Rejected(LiveReplaceBodyCompilationFailure.UNSUPPORTED_TARGET)
        return TargetCompilation.Compiled(file.text, bodyRange, original)
    }

    private fun parsesAsOneCompleteBlock(project: Project, proposed: ReplaceBodySourceText): Boolean {
        val wrapper = "fun __kast_replace_body__() ${proposed.value}"
        val parsed = KtPsiFactory(project, false).createFile("ReplaceBody.kt", wrapper)
        val function = parsed.declarations.singleOrNull() as? KtNamedFunction ?: return false
        val body = function.bodyBlockExpression ?: return false
        return parsed.text == wrapper &&
            function.text == wrapper &&
            function.hasBlockBody() &&
            body.text == proposed.value &&
            !body.hasContractCall() &&
            !PsiTreeUtil.hasErrorElements(parsed)
    }

    private fun org.jetbrains.kotlin.psi.KtBlockExpression.hasContractCall(): Boolean {
        val first = statements.firstOrNull()
        val call =
            when (first) {
                is KtCallExpression -> first
                is KtQualifiedExpression -> first.selectorExpression as? KtCallExpression
                else -> null
            }
        return call?.calleeExpression?.text == "contract"
    }

    private fun rejected(failure: LiveReplaceBodyCompilationFailure) = LiveReplaceBodyCompilation.Rejected(failure)

    private sealed interface TargetCompilation {
        data class Compiled(
            val sourceText: String,
            val bodyRange: ExactDeclarationTextRange,
            val originalBody: ExistingBodySourceText,
        ) : TargetCompilation

        data class Rejected(val failure: LiveReplaceBodyCompilationFailure) : TargetCompilation
    }
}
