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

internal fun SecureWorkspaceMutation.recoverMutationScratch(
    query: ParsedMutationScratchRecoveryQuery,
): MutationScratchRecoveryResult = withParentDirectoryDurabilityEvidence {
    val target = requireWorkspaceTarget(query.targetFilePath.toJavaPath(), IdeaWorkspaceMutation.TEXT_EDIT)
    val scratch = requireNotNull(requireScratchNames(target, query.scratch, IdeaWorkspaceMutation.TEXT_EDIT))
    val preimageBytes = (query.preimage as? MutationScratchRecoveryPreimage.Present)?.image?.copyBytes()
    val preimageHash = (query.preimage as? MutationScratchRecoveryPreimage.Present)?.image?.sha256
    val postimageHash = query.postimage.sha256
    val emptyHash = ExactFileImageSha256(FileHashing.sha256(byteArrayOf()))
    withParentDescriptor(target, createParents = false) { parent, fileName, api, platform ->
        val template = ScratchRecoveryTemplate.create(
            scratch = scratch,
            direction = query.scratchDirection,
            preimageHash = preimageHash,
            postimageHash = postimageHash,
            emptyHash = emptyHash,
        )
        val foreignPaths = descriptorEntryNames(parent, target, api, platform)
            .filter(::isMutationScratchInternalName)
            .filterNot(template.roleNames::contains)
            .map(target.parent::resolve)
        val targetObservation = observeScratchEntry(parent, fileName, target, api, platform)
        val roleObservations = template.roles.associateWith { slot ->
            observeScratchEntry(parent, slot.name, target, api, platform)
        }
        val blockers = preflightRecovery(
            target = target,
            action = query.action,
            template = template,
            targetObservation = targetObservation,
            roleObservations = roleObservations,
            foreignPaths = foreignPaths,
        )
        if (blockers.isNotEmpty()) throw recoveryBlocked(target, blockers)

        val desiredHash = when (query.action) {
            MutationScratchRecoveryAction.RESTORE_PREIMAGE -> preimageHash
            MutationScratchRecoveryAction.FINALIZE_POSTIMAGE -> postimageHash
        }
        if (!targetObservation.isExactTarget(desiredHash)) {
            val tombstoneFamily = if (targetObservation.state == MutationScratchState.PRESENT) {
                template.families.singleOrNull { family ->
                    family.expectedHash == targetObservation.sha256 &&
                        family.slots.all { slot -> roleObservations.getValue(slot).state == MutationScratchState.ABSENT }
                } ?: throw recoveryBlocked(target, listOf(target))
            } else {
                null
            }
            if (desiredHash != null) {
                val detachedTargetSlot = tombstoneFamily?.slots?.first()
                if (detachedTargetSlot != null) {
                    moveExactEntry(
                        parent = parent,
                        sourceName = fileName,
                        destinationName = detachedTargetSlot.name,
                        expected = targetObservation,
                        expectedHash = requireNotNull(targetObservation.sha256),
                        target = target,
                        api = api,
                        platform = platform,
                        phase = SecureWorkspaceRenamePhase.DETACH_TARGET,
                    )
                }
                val desiredFamily = template.familyFor(query.action)
                val existingSource = desiredFamily.slots.singleOrNull { slot ->
                    roleObservations.getValue(slot).isPresent(desiredHash)
                }
                val source = existingSource ?: materializeReversePreimage(
                    parent = parent,
                    target = target,
                    template = template,
                    preimageBytes = requireNotNull(preimageBytes),
                    expectedHash = desiredHash,
                    roleObservations = roleObservations,
                    api = api,
                    platform = platform,
                )
                val sourceObservation = if (source == existingSource) {
                    roleObservations.getValue(source)
                } else {
                    observeScratchEntry(parent, source.name, target, api, platform)
                }
                try {
                    moveExactEntry(
                        parent = parent,
                        sourceName = source.name,
                        destinationName = fileName,
                        expected = sourceObservation,
                        expectedHash = desiredHash,
                        target = target,
                        api = api,
                        platform = platform,
                        phase = SecureWorkspaceRenamePhase.FINAL_COMMIT,
                    )
                } catch (failure: Exception) {
                    var restorationFailure: Throwable? = null
                    if (detachedTargetSlot != null) {
                        val detached = observeScratchEntry(
                            parent,
                            detachedTargetSlot.name,
                            target,
                            api,
                            platform,
                        )
                        if (observeScratchEntry(parent, fileName, target, api, platform).state ==
                            MutationScratchState.ABSENT
                        ) {
                            restorationFailure = runCatching {
                                moveExactEntry(
                                    parent = parent,
                                    sourceName = detachedTargetSlot.name,
                                    destinationName = fileName,
                                    expected = detached,
                                    expectedHash = requireNotNull(targetObservation.sha256),
                                    target = target,
                                    api = api,
                                    platform = platform,
                                    phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
                                )
                            }.exceptionOrNull()
                        }
                    }
                    val evidence = recoveryBlocked(
                        target,
                        listOfNotNull(
                            target,
                            target.parent.resolve(source.name),
                            detachedTargetSlot?.let { target.parent.resolve(it.name) },
                        ),
                        failure = failure,
                        restorationFailure = restorationFailure,
                    )
                    failure.rethrowIfMutationCancellation(evidence)
                    restorationFailure?.rethrowIfMutationCancellation(evidence)
                    failure.rethrowIfParentDirectoryDurabilityFailed(evidence)
                    restorationFailure?.rethrowIfParentDirectoryDurabilityFailed(evidence)
                    if (detachedTargetSlot == null && failure is UnsafeWorkspaceMutationException) {
                        throw failure
                    }
                    evidence.initCause(failure)
                    restorationFailure?.let(evidence::addSuppressed)
                    throw evidence
                }
            } else if (tombstoneFamily != null) {
                removeExactEntryThroughSlot(
                    parent = parent,
                    sourceName = fileName,
                    sourceObservation = targetObservation,
                    cleanupName = tombstoneFamily.slots.first().name,
                    target = target,
                    api = api,
                    platform = platform,
                )
            }
        }

        template.families.forEach { family ->
            removePresentFamilyEntry(parent, family, target, api, platform)
        }

        val finalTarget = observeScratchEntry(parent, fileName, target, api, platform)
        if (!finalTarget.isExactTarget(desiredHash)) throw recoveryBlocked(target, listOf(target))
        val finalScratch = template.roles.map { slot ->
            val observation = observeScratchEntry(parent, slot.name, target, api, platform)
            if (observation.state != MutationScratchState.ABSENT) {
                throw recoveryBlocked(target, listOf(target.parent.resolve(slot.name)))
            }
            MutationScratchObservation(
                filePath = target.parent.resolve(slot.name).toString(),
                ownership = MutationScratchOwnership.OWNED,
                role = slot.role,
                state = MutationScratchState.ABSENT,
            )
        }
        MutationScratchRecoveryResult(
            mutationAttemptId = query.mutationAttemptId,
            action = query.action,
            outcome = when (query.action) {
                MutationScratchRecoveryAction.RESTORE_PREIMAGE -> MutationScratchRecoveryOutcome.RESTORED_PREIMAGE
                MutationScratchRecoveryAction.FINALIZE_POSTIMAGE -> MutationScratchRecoveryOutcome.FINALIZED_POSTIMAGE
            },
            targetState = if (desiredHash == null) {
                MutationScratchTargetState.ABSENT
            } else {
                MutationScratchTargetState.PRESENT
            },
            targetSha256 = desiredHash,
            scratchObservations = finalScratch,
        )
    }
}
