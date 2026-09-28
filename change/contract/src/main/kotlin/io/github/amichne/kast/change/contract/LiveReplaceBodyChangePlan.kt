package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.charset.StandardCharsets
import java.nio.file.Path

enum class ReplaceBodySourceFailure {
    BLANK,
    NON_CANONICAL_LINE_ENDING,
    CONTROL_CHARACTER,
}

/** Candidate complete Kotlin block. PSI validation happens at the installed compiler boundary. */
@JvmInline
value class ReplaceBodySourceText private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ReplaceBodySourceText, ReplaceBodySourceFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(ReplaceBodySourceFailure.BLANK)
                '\r' in raw -> Refinement.Rejected(ReplaceBodySourceFailure.NON_CANONICAL_LINE_ENDING)
                raw.any { it.isISOControl() && it != '\n' && it != '\t' } ->
                    Refinement.Rejected(ReplaceBodySourceFailure.CONTROL_CHARACTER)
                else -> Refinement.Refined(ReplaceBodySourceText(raw))
            }
    }
}

/** Exact block text taken from the selected PSI function. */
@JvmInline
value class ExistingBodySourceText private constructor(val value: String) {
    companion object {
        fun fromCompiler(raw: String): Refinement<ExistingBodySourceText, ReplaceBodyPreservationFailure> =
            if (raw.startsWith('{') && raw.endsWith('}')) Refinement.Refined(ExistingBodySourceText(raw))
            else Refinement.Rejected(ReplaceBodyPreservationFailure.INVALID_BODY)
    }
}

enum class ReplaceBodyPreservationFailure {
    INVALID_RANGE,
    INVALID_BODY,
    PREIMAGE_MISMATCH,
    SOURCE_HASH_MISMATCH,
}

/** Exact source split at the original block range. The postimage can change only that range. */
class ReplaceBodyPreservation
private constructor(
    val bodyRange: ExactDeclarationTextRange,
    val outsideBefore: String,
    val originalBody: ExistingBodySourceText,
    val outsideAfter: String,
    val signatureText: String,
    val replacement: ReplaceBodySourceText,
    val preimageContent: WorkspaceSourceContentHash,
    val expectedPostimage: WorkspaceSourceContentHash,
) {
    val preimage: String
        get() = outsideBefore + originalBody.value + outsideAfter

    val postimage: String
        get() = outsideBefore + replacement.value + outsideAfter

    companion object {
        fun capture(
            sourceText: String,
            targetRange: ExactDeclarationTextRange,
            bodyRange: ExactDeclarationTextRange,
            originalBody: ExistingBodySourceText,
            replacement: ReplaceBodySourceText,
            content: WorkspaceSourceContentHash,
        ): Refinement<ReplaceBodyPreservation, ReplaceBodyPreservationFailure> {
            if (
                bodyRange.startInclusive <= targetRange.startInclusive ||
                    bodyRange.endExclusive != targetRange.endExclusive ||
                    bodyRange.endExclusive > sourceText.length
            )
                return Refinement.Rejected(ReplaceBodyPreservationFailure.INVALID_RANGE)
            if (sourceText.substring(bodyRange.startInclusive, bodyRange.endExclusive) != originalBody.value) {
                return Refinement.Rejected(ReplaceBodyPreservationFailure.PREIMAGE_MISMATCH)
            }
            val actualHash = sha256Hex(sourceText.toByteArray(StandardCharsets.UTF_8))
            if (actualHash != content.value)
                return Refinement.Rejected(ReplaceBodyPreservationFailure.SOURCE_HASH_MISMATCH)
            val prefix = sourceText.substring(0, bodyRange.startInclusive)
            val suffix = sourceText.substring(bodyRange.endExclusive)
            val postimageHash =
                WorkspaceSourceContentHash.parse(
                    sha256Hex((prefix + replacement.value + suffix).toByteArray(StandardCharsets.UTF_8))
                )
            val expected =
                (postimageHash as? Refinement.Refined)?.value
                    ?: return Refinement.Rejected(ReplaceBodyPreservationFailure.SOURCE_HASH_MISMATCH)
            return Refinement.Refined(
                ReplaceBodyPreservation(
                    bodyRange,
                    prefix,
                    originalBody,
                    suffix,
                    sourceText.substring(targetRange.startInclusive, bodyRange.startInclusive),
                    replacement,
                    content,
                    expected,
                )
            )
        }
    }
}

/** A PSI-validated block, exact compiler-grounded target, and deterministic source image. */
class InstalledReplaceBodyIntent private constructor(val preservation: ReplaceBodyPreservation) {
    companion object {
        fun fromCompiler(preservation: ReplaceBodyPreservation): InstalledReplaceBodyIntent =
            InstalledReplaceBodyIntent(preservation)
    }
}

data class LiveReplaceBodyPlanRequest(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val selector: SymbolSelector,
    val content: WorkspaceSourceContentHash,
    val intent: InstalledReplaceBodyIntent,
)

sealed interface LiveReplaceBodyPlanningFailure {
    data class Target(val failure: MutationTargetAdmissionFailure) : LiveReplaceBodyPlanningFailure

    data class Basis(val failure: LiveChangeBasisFailure) : LiveReplaceBodyPlanningFailure

    data class Preservation(val failure: ReplaceBodyPreservationFailure) : LiveReplaceBodyPlanningFailure

    data object UnsupportedTarget : LiveReplaceBodyPlanningFailure
}

class AdmittedLiveReplaceBodyPlanInput
private constructor(
    val basis: LiveChangeBasis,
    val target: PlannedDeclarationIdentity,
    val content: WorkspaceSourceContentHash,
    val preservation: ReplaceBodyPreservation,
    val sourceRoot: SourceRoot,
) {
    companion object {
        fun admit(
            request: LiveReplaceBodyPlanRequest
        ): Refinement<AdmittedLiveReplaceBodyPlanInput, LiveReplaceBodyPlanningFailure> {
            if (request.selector.lease !== request.authority)
                return Refinement.Rejected(
                    LiveReplaceBodyPlanningFailure.Target(MutationTargetAdmissionFailure.STALE_STATE)
                )
            val basis =
                when (val observed = LiveChangeBasis.observe(request.authority.reference, request.model)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveReplaceBodyPlanningFailure.Basis(observed.failure))
                }
            val selector = request.selector
            if (selector.kind != CompilerSymbolKind.FUNCTION)
                return Refinement.Rejected(LiveReplaceBodyPlanningFailure.UnsupportedTarget)
            val file =
                selector.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(
                        LiveReplaceBodyPlanningFailure.Target(MutationTargetAdmissionFailure.ESCAPED_TARGET)
                    )
            return restore(
                basis,
                PlannedDeclarationIdentity.capture(selector, file),
                request.content,
                request.intent.preservation,
            )
        }

        @Suppress("LongMethod") // Keep source-root ownership and preimage admission in one proof boundary.
        internal fun restore(
            basis: LiveChangeBasis,
            target: PlannedDeclarationIdentity,
            content: WorkspaceSourceContentHash,
            preservation: ReplaceBodyPreservation,
        ): Refinement<AdmittedLiveReplaceBodyPlanInput, LiveReplaceBodyPlanningFailure> {
            if (target.kind != CompilerSymbolKind.FUNCTION)
                return Refinement.Rejected(LiveReplaceBodyPlanningFailure.UnsupportedTarget)
            val file = target.file
            val owners =
                basis.model.sourceRoots.filter { owner ->
                    val path = Path.of(owner.sourceRoot.value)
                    Path.of(file.path.value).let { it != path && it.startsWith(path) }
                }
            if (owners.size != 1) {
                val failure =
                    if (owners.isEmpty()) MutationTargetAdmissionFailure.ESCAPED_TARGET
                    else MutationTargetAdmissionFailure.AMBIGUOUS_OWNERSHIP
                return Refinement.Rejected(LiveReplaceBodyPlanningFailure.Target(failure))
            }
            val owner = owners.single()
            when (owner.provenance) {
                WorkspaceSourceRootProvenance.AUTHORED -> Unit
                WorkspaceSourceRootProvenance.GENERATED ->
                    return Refinement.Rejected(
                        LiveReplaceBodyPlanningFailure.Target(MutationTargetAdmissionFailure.GENERATED_SOURCE_ROOT)
                    )
                WorkspaceSourceRootProvenance.UNKNOWN ->
                    return Refinement.Rejected(
                        LiveReplaceBodyPlanningFailure.Target(MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT)
                    )
            }
            val sourceRoot =
                SourceRoot.admit(
                    GradleSourceRootEvidence(
                        ideaModuleName = owner.module.value,
                        workspaceRelativeBuildRoot = owner.project.buildRoot.value,
                        gradleProjectPath = owner.project.projectPath.value,
                        sourceSetName = owner.sourceSet.value,
                        workspaceRelativeSourceRoot =
                            Path.of(basis.reference.workspaceRoot.value)
                                .relativize(Path.of(owner.sourceRoot.value))
                                .toString()
                                .ifEmpty { "." },
                        provenance = SourceRootProvenance.Authored,
                    )
                )
            val root =
                (sourceRoot as? Refinement.Refined)?.value
                    ?: return Refinement.Rejected(
                        LiveReplaceBodyPlanningFailure.Target(MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT)
                    )
            if (
                preservation.preimageContent != content ||
                    preservation.bodyRange.startInclusive <= target.range.startInclusive ||
                    preservation.bodyRange.endExclusive != target.range.endExclusive
            ) {
                return Refinement.Rejected(
                    LiveReplaceBodyPlanningFailure.Preservation(ReplaceBodyPreservationFailure.PREIMAGE_MISMATCH)
                )
            }
            return Refinement.Refined(
                AdmittedLiveReplaceBodyPlanInput(
                    basis,
                    target,
                    content,
                    preservation,
                    root,
                )
            )
        }
    }
}

/** Diagnostics are verified for the changed source file. Outgoing calls may change with the body. */
class LiveReplaceBodyVerificationScope private constructor(val file: SymbolDiscoveryFileIdentity.Workspace) {
    companion object {
        fun target(file: SymbolDiscoveryFileIdentity.Workspace): LiveReplaceBodyVerificationScope =
            LiveReplaceBodyVerificationScope(file)
    }
}

class LiveReplaceBodyChangePlan
private constructor(
    private val input: AdmittedLiveReplaceBodyPlanInput,
    override val writes: PlannedMutationWriteSet,
) : LiveChangePlan {
    override val planId: ChangePlanId =
        ChangePlanId.fromCanonicalIdentity(
            buildString {
                appendPlanningField("LIVE_REPLACE_BODY_V1")
                appendPlanningField(input.basis.reference.workspaceRoot.value)
                appendPlanningField(input.basis.reference.version.toString())
                appendPlanningField(input.basis.reference.host.value.toString())
                appendPlanningField(input.basis.reference.epoch.value.toString())
                appendPlanningField(input.basis.reference.contentView.name)
                appendPlanningField(input.basis.model.sourceRoots.size.toString())
                input.basis.model.sourceRoots.forEach { root ->
                    appendPlanningField(root.module.value)
                    appendPlanningField(root.project.buildRoot.value)
                    appendPlanningField(root.project.projectPath.value)
                    appendPlanningField(root.sourceSet.value)
                    appendPlanningField(root.sourceRoot.value)
                    appendPlanningField(root.sourceKind.name)
                    appendPlanningField(root.provenance.name)
                }
                appendPlanningField(input.target.file.path.value)
                appendPlanningField(input.target.fingerprint.value)
                appendPlanningField(input.content.value)
                appendPlanningField(input.preservation.bodyRange.startInclusive.toString())
                appendPlanningField(input.preservation.bodyRange.endExclusive.toString())
                appendPlanningField(input.preservation.originalBody.value)
                appendPlanningField(input.preservation.replacement.value)
                appendPlanningField(input.preservation.expectedPostimage.value)
            }
        )
    override val basis: ChangePlanningBasis.Live = ChangePlanningBasis.Live(input.basis)
    override val target: PlannedDeclarationIdentity
        get() = input.target

    override val content: WorkspaceSourceContentHash
        get() = input.content

    val preservation: ReplaceBodyPreservation
        get() = input.preservation

    val body: ReplaceBodySourceText
        get() = preservation.replacement

    val expectedPostimage: WorkspaceSourceContentHash
        get() = preservation.expectedPostimage

    val verificationScope = LiveReplaceBodyVerificationScope.target(target.file)

    companion object {
        fun issue(input: AdmittedLiveReplaceBodyPlanInput): LiveReplaceBodyChangePlan =
            LiveReplaceBodyChangePlan(
                input,
                PlannedMutationWriteSet.singleton(
                    PlannedMutationWrite(
                        source = input.target.file,
                        sourceRoot = input.sourceRoot,
                        precondition = PlannedSourcePrecondition.Existing(input.content),
                        mutations =
                            listOf(
                                SourceTextMutation.ReplaceBody(
                                    input.preservation.bodyRange,
                                    input.preservation.originalBody,
                                    input.preservation.replacement,
                                )
                            ),
                    )
                ),
            )
    }
}

sealed interface LiveReplaceBodyPlanResult {
    data class Planned(val plan: LiveReplaceBodyChangePlan) : LiveReplaceBodyPlanResult

    data class Rejected(val failure: LiveReplaceBodyPlanningFailure) : LiveReplaceBodyPlanResult
}
