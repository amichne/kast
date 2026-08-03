package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.result.MutationScratchObservation
import io.github.amichne.kast.api.contract.result.MutationScratchOwnership
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryOutcome
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryResult
import io.github.amichne.kast.api.contract.result.MutationScratchRole
import io.github.amichne.kast.api.contract.result.MutationScratchState
import io.github.amichne.kast.api.contract.result.MutationScratchTargetState
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchRecoveryQuery
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import java.nio.file.Path

internal data class ScratchRoleSlot(
    val name: String,
    val role: MutationScratchRole,
)

internal data class ScratchRoleFamily(
    val expectedHash: ExactFileImageSha256?,
    val slots: List<ScratchRoleSlot>,
) {
    init {
        require(slots.size == 2) { "A mutation scratch family requires exactly two paired slots" }
    }
}

internal data class ScratchRecoveryTemplate(
    val quarantine: ScratchRoleFamily,
    val prepared: ScratchRoleFamily,
    val direction: MutationScratchDirection,
    val preimageHash: ExactFileImageSha256?,
    val postimageHash: ExactFileImageSha256,
) {
    val families: List<ScratchRoleFamily> = listOf(quarantine, prepared)
    val roles: List<ScratchRoleSlot> = listOf(
        quarantine.slots[0],
        prepared.slots[0],
        prepared.slots[1],
        quarantine.slots[1],
    )
    val roleNames: Set<String> = roles.mapTo(linkedSetOf(), ScratchRoleSlot::name)

    fun familyFor(action: MutationScratchRecoveryAction): ScratchRoleFamily = when (direction) {
        MutationScratchDirection.FORWARD -> when (action) {
            MutationScratchRecoveryAction.RESTORE_PREIMAGE -> quarantine
            MutationScratchRecoveryAction.FINALIZE_POSTIMAGE -> prepared
        }

        MutationScratchDirection.RESTORE_PREIMAGE -> when (action) {
            MutationScratchRecoveryAction.RESTORE_PREIMAGE -> prepared
            MutationScratchRecoveryAction.FINALIZE_POSTIMAGE -> quarantine
        }
    }

    companion object {
        fun create(
            scratch: SecureMutationScratchNames,
            direction: MutationScratchDirection,
            preimageHash: ExactFileImageSha256?,
            postimageHash: ExactFileImageSha256,
            emptyHash: ExactFileImageSha256,
        ): ScratchRecoveryTemplate {
            val quarantineHash = when (direction) {
                MutationScratchDirection.FORWARD -> preimageHash
                MutationScratchDirection.RESTORE_PREIMAGE -> postimageHash
            }
            val preparedHash = when (direction) {
                MutationScratchDirection.FORWARD -> postimageHash
                MutationScratchDirection.RESTORE_PREIMAGE -> preimageHash ?: emptyHash
            }
            return ScratchRecoveryTemplate(
                quarantine = ScratchRoleFamily(
                    expectedHash = quarantineHash,
                    slots = listOf(
                        ScratchRoleSlot(scratch.quarantineName, MutationScratchRole.QUARANTINE),
                        ScratchRoleSlot(scratch.quarantineCleanupName, MutationScratchRole.QUARANTINE_CLEANUP),
                    ),
                ),
                prepared = ScratchRoleFamily(
                    expectedHash = preparedHash,
                    slots = listOf(
                        ScratchRoleSlot(scratch.preparedName, MutationScratchRole.PREPARED),
                        ScratchRoleSlot(scratch.preparedCleanupName, MutationScratchRole.PREPARED_CLEANUP),
                    ),
                ),
                direction = direction,
                preimageHash = preimageHash,
                postimageHash = postimageHash,
            )
        }
    }
}

internal fun preflightRecovery(
    target: Path,
    action: MutationScratchRecoveryAction,
    template: ScratchRecoveryTemplate,
    targetObservation: SecureScratchEntryObservation,
    roleObservations: Map<ScratchRoleSlot, SecureScratchEntryObservation>,
    foreignPaths: List<Path>,
): List<Path> = buildList {
    addAll(foreignPaths)
    val allowedTargetHashes = template.families.mapNotNullTo(mutableSetOf()) { family -> family.expectedHash }
    if (targetObservation.state == MutationScratchState.UNSAFE ||
        targetObservation.state == MutationScratchState.PRESENT && targetObservation.sha256 !in allowedTargetHashes
    ) {
        add(target)
    }
    template.families.forEach { family ->
        val present = family.slots.filter { slot ->
            val observation = roleObservations.getValue(slot)
            when (observation.state) {
                MutationScratchState.ABSENT -> false
                MutationScratchState.PRESENT -> {
                    if (family.expectedHash == null || observation.sha256 != family.expectedHash) {
                        add(target.parent.resolve(slot.name))
                    }
                    true
                }

                MutationScratchState.UNSAFE -> {
                    add(target.parent.resolve(slot.name))
                    true
                }
            }
        }
        if (present.size > 1) {
            present.forEach { slot -> add(target.parent.resolve(slot.name)) }
        }
    }
    val desiredHash = when (action) {
        MutationScratchRecoveryAction.RESTORE_PREIMAGE -> template.preimageHash
        MutationScratchRecoveryAction.FINALIZE_POSTIMAGE -> template.postimageHash
    }
    val desiredFamily = template.familyFor(action)
    val desiredSources = desiredFamily.slots.filter { slot ->
        roleObservations.getValue(slot).isPresent(desiredFamily.expectedHash)
    }
    if (desiredHash != null) {
        if (targetObservation.isExactTarget(desiredHash)) {
            if (desiredSources.isNotEmpty()) {
                desiredSources.forEach { slot -> add(target.parent.resolve(slot.name)) }
            }
        } else if (desiredSources.size != 1 && !canMaterializeReversePreimage(
                action = action,
                template = template,
                targetObservation = targetObservation,
                roleObservations = roleObservations,
            )
        ) {
            add(target)
            desiredSources.forEach { slot -> add(target.parent.resolve(slot.name)) }
        }
    }
    if (targetObservation.state == MutationScratchState.PRESENT && !targetObservation.isExactTarget(desiredHash)) {
        val freeTombstoneFamilies = template.families.filter { family ->
            family.expectedHash == targetObservation.sha256 &&
                family.slots.all { slot -> roleObservations.getValue(slot).state == MutationScratchState.ABSENT }
        }
        if (freeTombstoneFamilies.size != 1) add(target)
    }
}.distinct()

internal fun canMaterializeReversePreimage(
    action: MutationScratchRecoveryAction,
    template: ScratchRecoveryTemplate,
    targetObservation: SecureScratchEntryObservation,
    roleObservations: Map<ScratchRoleSlot, SecureScratchEntryObservation>,
): Boolean = action == MutationScratchRecoveryAction.RESTORE_PREIMAGE &&
    template.direction == MutationScratchDirection.RESTORE_PREIMAGE &&
    template.preimageHash != null &&
    targetObservation.state == MutationScratchState.ABSENT &&
    template.prepared.slots.all { slot ->
        roleObservations.getValue(slot).state == MutationScratchState.ABSENT
    } &&
    template.quarantine.slots.count { slot ->
        roleObservations.getValue(slot).isPresent(template.postimageHash)
    } == 1
