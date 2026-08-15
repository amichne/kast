package io.github.amichne.kast.idea.backend.mutation.operations

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.server.change.VerifiedAddFileAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileIntent
import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanPreview
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanStage
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal sealed interface PlanAttempt {
    data class Planned(val plan: VerifiedAddFilePlan) : PlanAttempt
    data class Rejected(val result: VerifiedAddFileResult.Rejected) : PlanAttempt
}

internal sealed interface TargetAdmission {
    data object Admitted : TargetAdmission
    data class Rejected(val failure: VerifiedAddFileFailure) : TargetAdmission

    companion object {
        fun symlinkEscape(): Rejected = Rejected(VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE)
    }
}

internal fun rejected(
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
): VerifiedAddFileResult.Rejected = VerifiedAddFileResult.Rejected(progress, failure)

internal fun applyRejected(
    request: VerifiedAddFileApplyRequest,
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
    admittedPlanVersion: VerifiedAddFilePlanVersion = wireVersion(INITIAL_PLAN_VERSION),
): VerifiedAddFileApplyResult.Rejected = VerifiedAddFileApplyResult.Rejected(
    planId = request.planId,
    planVersion = admittedPlanVersion,
    stage = progress.toStage(),
    progress = progress,
    failure = failure,
)

internal fun VerifiedAddFileProgress.toStage(): VerifiedAddFilePlanStage = when (this) {
    VerifiedAddFileProgress.INTENT_ADMISSION,
    VerifiedAddFileProgress.PLANNING,
    -> VerifiedAddFilePlanStage.AWAITING_APPROVAL
    VerifiedAddFileProgress.REVALIDATION -> VerifiedAddFilePlanStage.APPROVED
    VerifiedAddFileProgress.RECOVERY_PREPARATION -> VerifiedAddFilePlanStage.RECOVERY_PREPARED
    VerifiedAddFileProgress.SOURCE_APPLICATION -> VerifiedAddFilePlanStage.APPLY_ADMITTED
    VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
    VerifiedAddFileProgress.PSI_ADMISSION,
    -> VerifiedAddFilePlanStage.APPLIED_UNVERIFIED
}

internal fun wireVersion(raw: Long): VerifiedAddFilePlanVersion =
    when (val refinement = VerifiedAddFilePlanVersion.refine(raw)) {
        is VerifiedAddFileRefinement.Refined -> refinement.value
        is VerifiedAddFileRefinement.Rejected -> error(
            "Static add-file lifecycle version violated its typed boundary: ${refinement.failure}",
        )
    }

internal fun AdditionProofIncompleteException.toVerifiedFailure(): VerifiedAddFileFailure = when {
    AdditionProofLimitation.TARGET_ALREADY_EXISTS in limitations -> VerifiedAddFileFailure.TARGET_ALREADY_EXISTS
    AdditionProofLimitation.GENERATED_SOURCE_READ_ONLY in limitations -> VerifiedAddFileFailure.TARGET_GENERATED
    AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS in limitations ->
        VerifiedAddFileFailure.TARGET_AMBIGUOUSLY_OWNED
    AdditionProofLimitation.OUTSIDE_WORKSPACE_AUTHORITY in limitations ||
        AdditionProofLimitation.HARD_EXCLUDED_MUTATION_TARGET in limitations ->
        VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE
    else -> VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID
}

internal fun PersistedVerifiedAddFilePlan.toWirePlan(): VerifiedAddFilePlanResult.Planned =
    VerifiedAddFilePlanResult.Planned(
        planId = planId,
        planVersion = initialVersion,
        preview = VerifiedAddFilePlanPreview(
            targetPath = planned.intent.targetPath,
            proposedContent = planned.intent.content,
            generation = planned.exact.proof.context.requiredGeneration,
        ),
    )

/** Proof transition: VerifiedAddFilePlan to VerifiedAddFilePlanId. */
internal fun verifiedAddFilePlanId(planned: VerifiedAddFilePlan): VerifiedAddFilePlanId {
    val identity = buildString {
        append(planned.intent.workspaceRoot.value)
        append("\u0000")
        append(planned.intent.targetPath.value)
        append("\u0000")
        append(planned.intent.content.value)
        append("\u0000")
        append(planned.exact.proof.context.requiredGeneration.value)
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val encoded = buildString(ADD_FILE_PLAN_ID_LENGTH) {
        append("af-")
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(PLAN_ID_HEX[value ushr 4])
            append(PLAN_ID_HEX[value and 0x0f])
        }
    }
    return when (val refinement = VerifiedAddFilePlanId.refine(encoded)) {
        is VerifiedAddFileRefinement.Refined -> refinement.value
        is VerifiedAddFileRefinement.Rejected ->
            error("SHA-256 add-file identity violated its typed boundary: ${refinement.failure}")
    }
}

/** Proof transition: VerifiedAddFilePlan to VerifiedAddFileRecoveryId. */
internal fun verifiedAddFileRecoveryId(planned: VerifiedAddFilePlan): VerifiedAddFileRecoveryId =
    VerifiedAddFileRecoveryId.fromPlan(verifiedAddFilePlanId(planned))

internal suspend fun planVerifiedAddFile(
    backend: KastIndexerBackend,
    intent: VerifiedAddFileIntent,
    progress: VerifiedAddFileProgress,
): PlanAttempt = try {
    val exact = backend.planAddFile(
        AddFilePlanQuery(
            targetPath = AdditionTargetPath.parse(intent.targetPath.value),
            proposedContent = intent.content.value,
        ).parsed(),
    )
    when (val admission = VerifiedAddFilePlan.admit(intent, exact)) {
        is VerifiedAddFileAdmission.Admitted -> PlanAttempt.Planned(admission.value)
        is VerifiedAddFileAdmission.Rejected -> PlanAttempt.Rejected(
            rejected(progress, admission.failure),
        )
    }
} catch (failure: AdditionProofIncompleteException) {
    PlanAttempt.Rejected(rejected(progress, failure.toVerifiedFailure()))
} catch (_: ProcessCanceledException) {
    PlanAttempt.Rejected(rejected(progress, VerifiedAddFileFailure.CANCELLED))
} catch (_: CancellationException) {
    PlanAttempt.Rejected(rejected(progress, VerifiedAddFileFailure.CANCELLED))
} catch (_: Exception) {
    PlanAttempt.Rejected(rejected(progress, VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID))
}

internal const val INITIAL_PLAN_VERSION = 0L
internal const val TERMINAL_PLAN_VERSION = 5L
private const val ADD_FILE_PLAN_ID_LENGTH = 67
private const val PLAN_ID_HEX = "0123456789abcdef"

/**
 * Proof transition: `(Path, VerifiedAddFileIntent) -> TargetAdmission`.
 *
 * Absence means canonical containment is proven. Presence means the closed symlink-escape failure
 * is retained before semantic planning can collapse it into a broader ownership error. Raw paths
 * are extracted only at this filesystem admission boundary.
 */
internal fun admitVerifiedAddFileTarget(
    workspaceRoot: Path,
    intent: VerifiedAddFileIntent,
): TargetAdmission {
    val target = Path.of(intent.targetPath.value)
    if (Files.isSymbolicLink(target)) return TargetAdmission.symlinkEscape()
    val parent = target.parent ?: return TargetAdmission.symlinkEscape()
    val canonicalRoot = runCatching { workspaceRoot.toRealPath() }.getOrNull()
        ?: return TargetAdmission.symlinkEscape()
    val canonicalParent = runCatching { parent.toRealPath() }.getOrNull()
        ?: return TargetAdmission.symlinkEscape()
    if (canonicalParent != parent.toAbsolutePath().normalize()) return TargetAdmission.symlinkEscape()
    val canonicalTarget = canonicalParent.resolve(target.fileName).normalize()
    return if (canonicalTarget.startsWith(canonicalRoot)) TargetAdmission.Admitted
    else TargetAdmission.symlinkEscape()
}
