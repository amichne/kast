package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.contract.LiveReplaceBodyChangePlan
import io.github.amichne.kast.change.contract.ReplaceBodyPreservation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

/** Exact source image proof. Every byte outside the selected block equals the planning preimage. */
class VerifiedReplaceBodyPostimage private constructor(val content: WorkspaceSourceContentHash) {
    companion object {
        @Suppress("ComplexCondition") // Check the full preimage and both unchanged surroundings as one source proof.
        fun admit(
            preservation: ReplaceBodyPreservation,
            observedText: String,
            observedContent: WorkspaceSourceContentHash,
        ): Refinement<VerifiedReplaceBodyPostimage, LiveVerificationFailure> {
            val start = preservation.bodyRange.startInclusive
            val end = start + preservation.replacement.value.length
            if (
                observedContent != preservation.expectedPostimage ||
                    observedText != preservation.postimage ||
                    observedText.length < end ||
                    observedText.substring(0, start) != preservation.outsideBefore ||
                    observedText.substring(end) != preservation.outsideAfter ||
                    !observedText.substring(0, start).endsWith(preservation.signatureText)
            )
                return Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
            return Refinement.Refined(VerifiedReplaceBodyPostimage(observedContent))
        }
    }
}

data class LiveReplaceBodyVerificationInput(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val postimage: VerifiedReplaceBodyPostimage,
    val anchor: SymbolSelector,
    val diagnostics: DiagnosticCheckResult,
)

/** Complete saved-source, compiler identity, and exact-file diagnostics proof after one body replacement. */
class CompleteLiveReplaceBodyVerification
private constructor(
    val plan: LiveReplaceBodyChangePlan,
    val resulting: LiveChangeBasis,
    val postimage: WorkspaceSourceContentHash,
    val freshReference: SymbolSelector,
    val diagnostics: DiagnosticCheckResult.Complete,
) {
    companion object {
        @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
        // A complete proof binds owner, model, compiler anchor, source image, and exact-file diagnostics.
        fun admit(
            plan: LiveReplaceBodyChangePlan,
            input: LiveReplaceBodyVerificationInput,
        ): Refinement<CompleteLiveReplaceBodyVerification, LiveVerificationFailure> {
            val prior = plan.basis.observation
            val current = input.authority
            if (
                current.reference.host != prior.reference.host ||
                    current.workspaceRoot != prior.reference.workspaceRoot ||
                    current.reference.version != LiveSemanticReadReference.VERSION
            )
                return Refinement.Rejected(LiveVerificationFailure.ORIGINAL_OWNER_MISMATCH)
            if (
                input.model.workspaceRoot != prior.model.workspaceRoot ||
                    input.model.sourceRoots != prior.model.sourceRoots
            )
                return Refinement.Rejected(LiveVerificationFailure.MODEL_MOVED)
            if (
                current.reference.contentView != IdeReadContentView.SAVED_PSI_COMMITTED ||
                    input.anchor.lease !== current
            )
                return Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
            if (input.postimage.content != plan.expectedPostimage)
                return Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
            val anchor = input.anchor
            if (
                anchor.file != plan.target.file ||
                    anchor.scope != plan.target.scope ||
                    anchor.constraints != plan.target.constraints ||
                    CompilerReobservedMutationAnchor.admit(
                        plan.target.evidence,
                        CompilerGroundedSymbolEvidence.fromSelector(anchor),
                    ) is Refinement.Rejected
            )
                return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
            val complete =
                input.diagnostics as? DiagnosticCheckResult.Complete
                    ?: return Refinement.Rejected(LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED)
            if (
                complete.batch.scope.lease !== current ||
                    complete.batch.scope.files.map { it.value } != listOf(plan.verificationScope.file.path.value) ||
                    complete.batch.facts.any { it.severity == DiagnosticSeverity.ERROR }
            )
                return Refinement.Rejected(LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED)
            val resulting =
                when (val observed = LiveChangeBasis.observe(current.reference, input.model)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
                }
            return Refinement.Refined(
                CompleteLiveReplaceBodyVerification(
                    plan,
                    resulting,
                    input.postimage.content,
                    anchor,
                    complete,
                )
            )
        }
    }
}
