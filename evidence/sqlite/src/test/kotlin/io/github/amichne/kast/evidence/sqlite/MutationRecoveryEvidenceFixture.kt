package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.AppliedRecoveryWriteSet
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryPreparation
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.PlannedRecoveryWrite
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoveryRequirement
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets

internal class MutationRecoveryEvidenceFixture(
    discriminator: Char = 'a',
) {
    val binding = MutationPlanBinding.parse(discriminator.toString().repeat(64)).refined()
    val source = RecoverySourcePath.parse(
        "/workspace/app/src/main/kotlin/sample/Service.kt",
    ).refined()
    val preimage = RecoveryPreimage.fromBoundary(
        "package sample\nclass Service".toByteArray(StandardCharsets.UTF_8),
    )
    val preparation = MutationRecoveryPreparation.admit(
        binding,
        listOf(PlannedRecoveryWrite(source, preimage)),
    ).refined()
    val prepared = MutationRecoveryRecord.prepare(preparation)
    val writeSet = AppliedRecoveryWriteSet.admit(
        preparation.plannedWrites,
        listOf(source),
    ).refined()
    val applied = MutationRecoveryRecord.recordApplied(prepared, writeSet).refined()
    val rolledBack = MutationRecoveryRecord.rolledBack(applied)
    val recoveryRequired = MutationRecoveryRecord.recoveryRequired(
        applied,
        RecoveryRequirement.ROLLBACK_REJECTED,
    )
}

internal fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
