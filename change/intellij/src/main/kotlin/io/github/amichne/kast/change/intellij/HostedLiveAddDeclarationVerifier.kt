package io.github.amichne.kast.change.intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.ObservedAbsentMutationSource
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.verify.CompleteLiveAddDeclarationVerification
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObservation
import io.github.amichne.kast.change.verify.LiveAddDeclarationVerificationInput
import io.github.amichne.kast.change.verify.LiveAddDeclarationVerificationPorts
import io.github.amichne.kast.change.verify.LiveAddDeclarationVerificationReplay
import io.github.amichne.kast.change.verify.LiveVerificationFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Explicit compiler and source effects scoped to the saved/committed original-owner read after mutation. */
object HostedLiveAddDeclarationVerifier {
    private val logger = Logger.getInstance(HostedLiveAddDeclarationVerifier::class.java)

    private enum class Stage {
        CURRENT_STATE,
        SOURCE,
        COMPILER,
        REPLAY,
        PROOF,
    }

    private enum class Outcome {
        COMPLETED,
        REJECTED,
    }

    suspend fun verify(
        project: Project,
        context: HostedSemanticReadContext,
        plan: LiveAddDeclarationChangePlan,
        expectedPostimage: WorkspaceSourceContentHash,
        ports: LiveAddDeclarationVerificationPorts,
    ): Refinement<CompleteLiveAddDeclarationVerification, LiveVerificationFailure> {
        when (val current = validateCurrentBasis(context, plan)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return rejected(Stage.CURRENT_STATE, current.failure)
        }
        val source =
            when (
                val observed =
                    observePostimage(project = project, context = context, plan = plan, expected = expectedPostimage)
            ) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return rejected(Stage.SOURCE, observed.failure)
            }
        val semantic =
            when (val observed = observeLiveAddDeclaration(project, context.authority, plan)) {
                is HostedAddDeclarationSemanticObservation.Observed -> observed.evidence
                is HostedAddDeclarationSemanticObservation.Rejected ->
                    return rejected(Stage.COMPILER, LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
            }
        signal(Stage.COMPILER, Outcome.COMPLETED)
        val replayed =
            when (val observed = LiveAddDeclarationVerificationReplay.observe(plan, semantic.anchor, ports)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return rejected(Stage.REPLAY, observed.failure)
            }
        when (
            val observed =
                observePostimage(project = project, context = context, plan = plan, expected = source.content)
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return rejected(Stage.SOURCE, observed.failure)
        }
        if (context.validation.validate(context.authority) != SemanticReadValidation.CURRENT) {
            return rejected(Stage.CURRENT_STATE, LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
        }
        val proof =
            CompleteLiveAddDeclarationVerification.admit(
                plan = plan,
                input =
                    LiveAddDeclarationVerificationInput(
                        authority = context.authority,
                        model = context.model,
                        expectedPostimage = expectedPostimage,
                        observedSource = source,
                        semantic = semantic,
                        evidence = replayed,
                    ),
            )
        return observedProof(proof)
    }

    private fun observedProof(
        proof: Refinement<CompleteLiveAddDeclarationVerification, LiveVerificationFailure>
    ): Refinement<CompleteLiveAddDeclarationVerification, LiveVerificationFailure> {
        when (proof) {
            is Refinement.Refined -> signal(Stage.PROOF, Outcome.COMPLETED)
            is Refinement.Rejected -> return rejected(Stage.PROOF, proof.failure)
        }
        return proof
    }

    private suspend fun validateCurrentBasis(
        context: HostedSemanticReadContext,
        plan: LiveAddDeclarationChangePlan,
    ): Refinement<Unit, LiveVerificationFailure> {
        if (context.validation.validate(context.authority) != SemanticReadValidation.CURRENT) {
            return Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
        }
        val original = plan.basis.observation
        if (
            context.authority.workspaceRoot != original.reference.workspaceRoot ||
                context.authority.reference.host != original.reference.host
        ) {
            return Refinement.Rejected(LiveVerificationFailure.ORIGINAL_OWNER_MISMATCH)
        }
        if (
            context.model.workspaceRoot != original.model.workspaceRoot ||
                context.model.sourceRoots != original.model.sourceRoots
        ) {
            return Refinement.Rejected(LiveVerificationFailure.MODEL_MOVED)
        }
        return Refinement.Refined(Unit)
    }

    private fun observePostimage(
        project: Project,
        context: HostedSemanticReadContext,
        plan: LiveAddDeclarationChangePlan,
        expected: WorkspaceSourceContentHash,
    ): Refinement<ObservedMutationSource, LiveVerificationFailure> {
        val rejected = Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
        val source =
            when (val observed = IntellijChangeSourceAdapter(project).observe(plan.target.file, context.limits)) {
                is SourceObservationResult.Rejected -> return rejected
                is SourceObservationResult.Observed ->
                    when (val content = observed.source) {
                        is ObservedAbsentMutationSource -> return rejected
                        is ObservedMutationSource -> content
                    }
            }
        return if (source.source == plan.target.file && source.content == expected) Refinement.Refined(source)
        else rejected
    }

    private fun rejected(stage: Stage, failure: LiveVerificationFailure): Refinement.Rejected<LiveVerificationFailure> {
        logger.info("live_add_declaration_verification stage=$stage outcome=${Outcome.REJECTED} reason=$failure")
        return Refinement.Rejected(failure)
    }

    private fun signal(stage: Stage, outcome: Outcome) {
        logger.info("live_add_declaration_verification stage=$stage outcome=$outcome")
    }
}
