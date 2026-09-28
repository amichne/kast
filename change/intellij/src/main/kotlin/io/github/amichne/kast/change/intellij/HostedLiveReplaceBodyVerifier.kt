package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.contract.LiveReplaceBodyChangePlan
import io.github.amichne.kast.change.verify.CompleteLiveReplaceBodyVerification
import io.github.amichne.kast.change.verify.LiveReplaceBodyVerificationInput
import io.github.amichne.kast.change.verify.LiveVerificationFailure
import io.github.amichne.kast.change.verify.VerifiedReplaceBodyPostimage
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Verifies the exact postimage and unchanged callable identity in a fresh original-owner read. */
object HostedLiveReplaceBodyVerifier {
    private val logger = Logger.getInstance(HostedLiveReplaceBodyVerifier::class.java)

    private enum class Stage {
        CURRENT_STATE,
        SOURCE,
        COMPILER,
        DIAGNOSTICS,
        PROOF,
    }

    private enum class Outcome {
        COMPLETED,
        REJECTED,
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    // Source, compiler, diagnostics, and final proof are one ordered live read.
    suspend fun verify(
        project: Project,
        context: HostedSemanticReadContext,
        plan: LiveReplaceBodyChangePlan,
        expectedPostimage: WorkspaceSourceContentHash,
        diagnostics: DiagnosticOperations,
    ): Refinement<CompleteLiveReplaceBodyVerification, LiveVerificationFailure> {
        if (context.validation.validate(context.authority) != SemanticReadValidation.CURRENT)
            return rejected(Stage.CURRENT_STATE, LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
        val prior = plan.basis.observation
        if (
            context.authority.workspaceRoot != prior.reference.workspaceRoot ||
                context.authority.reference.host != prior.reference.host
        )
            return rejected(Stage.CURRENT_STATE, LiveVerificationFailure.ORIGINAL_OWNER_MISMATCH)
        if (
            context.model.workspaceRoot != prior.model.workspaceRoot ||
                context.model.sourceRoots != prior.model.sourceRoots
        )
            return rejected(Stage.CURRENT_STATE, LiveVerificationFailure.MODEL_MOVED)
        if (expectedPostimage != plan.expectedPostimage)
            return rejected(Stage.SOURCE, LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
        val source =
            when (val observed = observeSource(project, context, plan, expectedPostimage)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return rejected(Stage.SOURCE, observed.failure)
            }
        val semantic =
            when (val observed = observeCompiler(project, context, plan)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return rejected(Stage.COMPILER, observed.failure)
            }
        val postimage =
            when (
                val admitted =
                    VerifiedReplaceBodyPostimage.admit(
                        plan.preservation,
                        semantic.text,
                        source.content,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(Stage.SOURCE, admitted.failure)
            }
        val scope =
            when (
                val admitted =
                    DiagnosticScope.fromCanonicalPaths(
                        context.authority,
                        listOf(Path.of(plan.verificationScope.file.path.value)),
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return rejected(Stage.DIAGNOSTICS, LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED)
            }
        val diagnostic = diagnostics.check(DiagnosticCheckRequest(scope))
        val complete =
            diagnostic as? DiagnosticCheckResult.Complete
                ?: return rejected(Stage.DIAGNOSTICS, LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED)
        if (
            complete.batch.scope.lease !== context.authority ||
                complete.batch.scope.files.map { it.value } != listOf(plan.verificationScope.file.path.value) ||
                complete.batch.facts.any { it.severity == DiagnosticSeverity.ERROR }
        )
            return rejected(Stage.DIAGNOSTICS, LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED)
        signal(Stage.DIAGNOSTICS, Outcome.COMPLETED)
        when (val observed = observeSource(project, context, plan, expectedPostimage)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return rejected(Stage.SOURCE, observed.failure)
        }
        if (context.validation.validate(context.authority) != SemanticReadValidation.CURRENT)
            return rejected(Stage.CURRENT_STATE, LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
        val proof =
            CompleteLiveReplaceBodyVerification.admit(
                plan,
                LiveReplaceBodyVerificationInput(
                    authority = context.authority,
                    model = context.model,
                    postimage = postimage,
                    anchor = semantic.anchor,
                    diagnostics = diagnostic,
                ),
            )
        return when (proof) {
            is Refinement.Refined -> {
                signal(Stage.PROOF, Outcome.COMPLETED)
                proof
            }
            is Refinement.Rejected -> rejected(Stage.PROOF, proof.failure)
        }
    }

    private fun observeSource(
        project: Project,
        context: HostedSemanticReadContext,
        plan: LiveReplaceBodyChangePlan,
        expected: WorkspaceSourceContentHash,
    ): Refinement<ObservedMutationSource, LiveVerificationFailure> {
        val observed = IntellijChangeSourceAdapter(project).observe(plan.target.file, context.limits)
        val source =
            (observed as? SourceObservationResult.Observed)?.source as? ObservedMutationSource
                ?: return Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
        if (source.source != plan.target.file || source.content != expected)
            return Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
        signal(Stage.SOURCE, Outcome.COMPLETED)
        return Refinement.Refined(source)
    }

    private fun observeCompiler(
        project: Project,
        context: HostedSemanticReadContext,
        plan: LiveReplaceBodyChangePlan,
    ): Refinement<BodySemanticObservation, LiveVerificationFailure> {
        if (project.isDisposed || DumbService.getInstance(project).isDumb)
            return Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
        return try {
            ReadAction.nonBlocking<Refinement<BodySemanticObservation, LiveVerificationFailure>> {
                    observeCompilerRead(project, context, plan)
                }
                .inSmartMode(project)
                .executeSynchronously()
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        }
    }

    private fun observeCompilerRead(
        project: Project,
        context: HostedSemanticReadContext,
        plan: LiveReplaceBodyChangePlan,
    ): Refinement<BodySemanticObservation, LiveVerificationFailure> {
        val virtual =
            LocalFileSystem.getInstance().findFileByNioFile(Path.of(plan.target.file.path.value))
                ?: return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        val file =
            PsiManager.getInstance(project).findFile(virtual) as? KtFile
                ?: return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        val fileIdentity =
            SymbolDiscoveryFileIdentity.fromBoundary(
                context.authority.workspaceRoot,
                Path.of(virtual.path),
                virtual.url,
            )
        if ((fileIdentity as? Refinement.Refined)?.value != plan.target.file)
            return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        val functions =
            PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).filter { function ->
                function.name == plan.target.evidence.name.value &&
                    function.textRange.startOffset == plan.target.range.startInclusive
            }
        val function =
            functions.singleOrNull()
                ?: return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        val body =
            function.bodyBlockExpression
                ?: return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        if (
            body.text != plan.body.value ||
                file.text.substring(function.textRange.startOffset, body.textRange.startOffset) !=
                    plan.preservation.signatureText
        )
            return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        val evidence =
            function.compilerEvidence(plan.target.file)
                ?: return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        val anchor = SymbolSelector.issue(context.authority, plan.target.scope, evidence, plan.target.constraints)
        signal(Stage.COMPILER, Outcome.COMPLETED)
        return Refinement.Refined(BodySemanticObservation(file.text, anchor))
    }

    private fun rejected(stage: Stage, failure: LiveVerificationFailure): Refinement.Rejected<LiveVerificationFailure> {
        logger.info("live_replace_body_verification stage=$stage outcome=${Outcome.REJECTED} reason=$failure")
        return Refinement.Rejected(failure)
    }

    private fun signal(stage: Stage, outcome: Outcome) {
        logger.info("live_replace_body_verification stage=$stage outcome=$outcome")
    }

    private data class BodySemanticObservation(val text: String, val anchor: SymbolSelector)
}
