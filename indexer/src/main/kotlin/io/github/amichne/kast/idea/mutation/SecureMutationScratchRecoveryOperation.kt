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
): MutationScratchRecoveryResult {
    val target = requireWorkspaceTarget(query.targetFilePath.toJavaPath(), IdeaWorkspaceMutation.TEXT_EDIT)
    val scratch = requireNotNull(requireScratchNames(target, query.scratch, IdeaWorkspaceMutation.TEXT_EDIT))
    val preimageBytes = (query.preimage as? MutationScratchRecoveryPreimage.Present)?.image?.copyBytes()
    val preimageHash = (query.preimage as? MutationScratchRecoveryPreimage.Present)?.image?.sha256
    val postimageHash = query.postimage.sha256
    val emptyHash = ExactFileImageSha256(FileHashing.sha256(byteArrayOf()))
    return withParentDescriptor(target, createParents = false) { parent, fileName, api, platform ->
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
                    )
                    failure.rethrowIfMutationCancellation(evidence)
                    restorationFailure?.rethrowIfMutationCancellation(evidence)
                    throw failure
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

private data class ScratchRoleSlot(
    val name: String,
    val role: MutationScratchRole,
)

private data class ScratchRoleFamily(
    val expectedHash: ExactFileImageSha256?,
    val slots: List<ScratchRoleSlot>,
) {
    init {
        require(slots.size == 2) { "A mutation scratch family requires exactly two paired slots" }
    }
}

private data class ScratchRecoveryTemplate(
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

private fun preflightRecovery(
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

private fun canMaterializeReversePreimage(
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

private fun SecureWorkspaceMutation.materializeReversePreimage(
    parent: NativeDescriptor,
    target: Path,
    template: ScratchRecoveryTemplate,
    preimageBytes: ByteArray,
    expectedHash: ExactFileImageSha256,
    roleObservations: Map<ScratchRoleSlot, SecureScratchEntryObservation>,
    api: PosixFileApi,
    platform: PosixPlatform,
): ScratchRoleSlot {
    if (FileHashing.sha256(preimageBytes) != expectedHash.value) {
        throw recoveryBlocked(target, listOf(target))
    }
    val postSource = template.quarantine.slots.single { slot ->
        roleObservations.getValue(slot).isPresent(template.postimageHash)
    }
    val targetSlot = template.prepared.slots.first()
    val cleanupSlot = template.prepared.slots.last()
    val mode = roleObservations.getValue(postSource).mode
        ?: throw recoveryBlocked(target, listOf(target.parent.resolve(postSource.name)))
    createPreparedFile(
        parent = parent,
        target = target,
        content = preimageBytes,
        mode = mode.permissionBits,
        api = api,
        platform = platform,
        preparedName = targetSlot.name,
        onUntrackedPreparationFailure = { failure -> throw failure },
        onPreparationFailure = { failedPrepared, failure ->
            val cleanup = removeExactNamedEntry(
                parent = parent,
                entry = failedPrepared,
                target = target,
                api = api,
                platform = platform,
                cleanupName = cleanupSlot.name,
                expectedSha256 = expectedHash,
            )
            failedPrepared.close()
            val evidence = UnsafeWorkspaceMutationException(
                message = "Mutation scratch recovery could not materialize the exact reverse preimage",
                details = failureDetails(target, "recover-materialize-preimage") +
                    mapOf("cause" to (failure.message ?: failure::class.java.simpleName)) +
                    cleanup.conflictDetails(),
            )
            failure.rethrowIfMutationCancellation(evidence)
            cleanup.rethrowIfMutationCancellation(evidence)
            throw evidence
        },
    ).close()
    val observation = observeScratchEntry(parent, targetSlot.name, target, api, platform)
    if (!observation.isPresent(expectedHash)) {
        throw recoveryBlocked(target, listOf(target.parent.resolve(targetSlot.name)))
    }
    return targetSlot
}

private fun SecureWorkspaceMutation.removePresentFamilyEntry(
    parent: NativeDescriptor,
    family: ScratchRoleFamily,
    target: Path,
    api: PosixFileApi,
    platform: PosixPlatform,
) {
    val current = family.slots.map { slot ->
        slot to observeScratchEntry(parent, slot.name, target, api, platform)
    }
    val present = current.filter { (_, observation) -> observation.state == MutationScratchState.PRESENT }
    if (present.isEmpty()) return
    if (present.size != 1 || family.expectedHash == null || !present.single().second.isPresent(family.expectedHash)) {
        throw recoveryBlocked(target, family.slots.map { slot -> target.parent.resolve(slot.name) })
    }
    val (source, observation) = present.single()
    val cleanup = family.slots.single { slot -> slot != source }
    if (current.single { (slot, _) -> slot == cleanup }.second.state != MutationScratchState.ABSENT) {
        throw recoveryBlocked(target, family.slots.map { slot -> target.parent.resolve(slot.name) })
    }
    removeExactEntryThroughSlot(
        parent = parent,
        sourceName = source.name,
        sourceObservation = observation,
        cleanupName = cleanup.name,
        target = target,
        api = api,
        platform = platform,
    )
}

private fun SecureWorkspaceMutation.removeExactEntryThroughSlot(
    parent: NativeDescriptor,
    sourceName: String,
    sourceObservation: SecureScratchEntryObservation,
    cleanupName: String,
    target: Path,
    api: PosixFileApi,
    platform: PosixPlatform,
) {
    val identity = sourceObservation.identity
        ?: throw recoveryBlocked(target, listOf(target.parent.resolve(sourceName)))
    val expectedSha256 = sourceObservation.sha256
        ?: throw recoveryBlocked(target, listOf(target.parent.resolve(sourceName)))
    val result = removeExactName(
        parent = parent,
        sourceName = sourceName,
        expectedIdentity = identity,
        target = target,
        api = api,
        platform = platform,
        exactCleanupName = cleanupName,
        expectedSha256 = expectedSha256,
    )
    when (result) {
        CleanupResult.Removed -> Unit
        is CleanupResult.Cancelled -> {
            result.cancellation.addSuppressed(recoveryBlocked(target, result.recoveryFilePaths))
            throw result.cancellation
        }
        is CleanupResult.Retained -> throw recoveryBlocked(target, result.recoveryFilePaths)
    }
}

private fun SecureWorkspaceMutation.moveExactEntry(
    parent: NativeDescriptor,
    sourceName: String,
    destinationName: String,
    expected: SecureScratchEntryObservation,
    expectedHash: ExactFileImageSha256,
    target: Path,
    api: PosixFileApi,
    platform: PosixPlatform,
) {
    val rechecked = observeScratchEntry(parent, sourceName, target, api, platform)
    if (!rechecked.sameExactEntry(expected, expectedHash)) {
        throw recoveryBlocked(target, listOf(target.parent.resolve(sourceName)))
    }
    when (
        renameNoReplace(
            parent = parent,
            sourceName = sourceName,
            destinationName = destinationName,
            target = target,
            platform = platform,
            phase = SecureWorkspaceRenamePhase.FINAL_COMMIT,
        )
    ) {
        RenameNoReplaceOutcome.MOVED -> Unit
        RenameNoReplaceOutcome.DESTINATION_EXISTS,
        RenameNoReplaceOutcome.SOURCE_MISSING,
        -> throw recoveryBlocked(target, listOf(target.parent.resolve(sourceName), target))
    }
    val moved = observeScratchEntry(parent, destinationName, target, api, platform)
    if (!moved.sameExactEntry(expected, expectedHash)) {
        val evidence = recoveryBlocked(target, listOf(target.parent.resolve(sourceName), target))
        val restorationFailure = runCatching {
            renameNoReplace(
                parent = parent,
                sourceName = destinationName,
                destinationName = sourceName,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
            )
        }.exceptionOrNull()
        restorationFailure?.rethrowIfMutationCancellation(evidence)
        throw evidence
    }
}

private fun SecureScratchEntryObservation.sameExactEntry(
    expected: SecureScratchEntryObservation,
    expectedHash: ExactFileImageSha256,
): Boolean = isPresent(expectedHash) && identity != null && identity == expected.identity

private fun SecureScratchEntryObservation.isExactTarget(expectedHash: ExactFileImageSha256?): Boolean =
    if (expectedHash == null) state == MutationScratchState.ABSENT else isPresent(expectedHash)

private fun SecureScratchEntryObservation.isPresent(expectedHash: ExactFileImageSha256?): Boolean =
    expectedHash != null &&
        state == MutationScratchState.PRESENT &&
        sha256 == expectedHash &&
        identity != null

private fun recoveryBlocked(target: Path, retainedPaths: List<Path>): UnsafeWorkspaceMutationException =
    UnsafeWorkspaceMutationException(
        message = "Mutation scratch recovery could not prove a journal-authorized final state",
        details = buildMap {
            val paths = retainedPaths.distinct().sorted()
            put("filePath", target.toString())
            put("recoveryFilePathCount", paths.size.toString())
            paths.forEachIndexed { index, path -> put("recoveryFilePath.$index", path.toString()) }
        },
    )
