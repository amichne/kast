package io.github.amichne.kast.idea.backend.mutation.operations

import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanPreview
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import java.security.MessageDigest

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

/**
 * Proof transition: `VerifiedAddFilePlan -> VerifiedAddFilePlanId`.
 *
 * Establishes a distinct deterministic add-file identity over workspace, target, content, and
 * semantic generation. Raw values are extracted only at this persistence identity boundary.
 */
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
        is io.github.amichne.kast.server.change.VerifiedAddFileRefinement.Refined -> refinement.value
        is io.github.amichne.kast.server.change.VerifiedAddFileRefinement.Rejected -> error(
            "SHA-256 add-file identity violated its typed boundary: ${refinement.failure}",
        )
    }
}

/**
 * Proof transition: `VerifiedAddFilePlan -> VerifiedAddFileRecoveryId`.
 *
 * Establishes a typed recovery-capability identity distinct from [VerifiedAddFilePlanId], derived
 * from the exact strong plan. Raw values are extracted only at this persistence digest boundary.
 */
internal fun verifiedAddFileRecoveryId(planned: VerifiedAddFilePlan): VerifiedAddFileRecoveryId =
    VerifiedAddFileRecoveryId.fromPlan(verifiedAddFilePlanId(planned))

private const val ADD_FILE_PLAN_ID_LENGTH = 67
private const val PLAN_ID_HEX = "0123456789abcdef"
