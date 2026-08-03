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

internal fun SecureWorkspaceMutation.materializeReversePreimage(
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

internal fun SecureWorkspaceMutation.removePresentFamilyEntry(
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

internal fun SecureWorkspaceMutation.removeExactEntryThroughSlot(
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
        is CleanupResult.Retained -> {
            val evidence = recoveryBlocked(
                target = target,
                retainedPaths = result.recoveryFilePaths,
                failure = UnsafeWorkspaceMutationException(
                    message = result.reason,
                    details = emptyMap(),
                ),
                restorationFailure = result.restorationFailure,
            )
            result.restorationFailure?.let(evidence::addSuppressed)
            throw evidence
        }
    }
}

internal fun SecureWorkspaceMutation.moveExactEntry(
    parent: NativeDescriptor,
    sourceName: String,
    destinationName: String,
    expected: SecureScratchEntryObservation,
    expectedHash: ExactFileImageSha256,
    target: Path,
    api: PosixFileApi,
    platform: PosixPlatform,
    phase: SecureWorkspaceRenamePhase,
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
            phase = phase,
        )
    ) {
        RenameNoReplaceOutcome.MOVED -> Unit
        RenameNoReplaceOutcome.DESTINATION_EXISTS,
        RenameNoReplaceOutcome.SOURCE_MISSING,
        -> throw recoveryBlocked(target, listOf(target.parent.resolve(sourceName), target))
    }
    val moved = observeScratchEntry(parent, destinationName, target, api, platform)
    if (!moved.sameExactEntry(expected, expectedHash)) {
        val restorationFailure = runCatching {
            renameNoReplace(
                parent = parent,
                sourceName = destinationName,
                destinationName = sourceName,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
            )
        }.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    RenameNoReplaceOutcome.MOVED -> null
                    RenameNoReplaceOutcome.DESTINATION_EXISTS,
                    RenameNoReplaceOutcome.SOURCE_MISSING,
                    -> UnsafeWorkspaceMutationException(
                        message = "Scratch post-move verification could not restore the prior namespace",
                        details = failureDetails(target, "scratch-post-move-restoration") + mapOf(
                            "renameOutcome" to outcome.name,
                        ),
                    )
                }
            },
            onFailure = { failure -> failure },
        )
        val evidence = recoveryBlocked(
            target = target,
            retainedPaths = listOf(target.parent.resolve(sourceName), target),
            restorationFailure = restorationFailure,
        )
        restorationFailure?.rethrowIfMutationCancellation(evidence)
        restorationFailure?.rethrowIfParentDirectoryDurabilityFailed(evidence)
        restorationFailure?.let(evidence::addSuppressed)
        throw evidence
    }
}

internal fun SecureScratchEntryObservation.sameExactEntry(
    expected: SecureScratchEntryObservation,
    expectedHash: ExactFileImageSha256,
): Boolean = isPresent(expectedHash) && identity != null && identity == expected.identity

internal fun SecureScratchEntryObservation.isExactTarget(expectedHash: ExactFileImageSha256?): Boolean =
    if (expectedHash == null) state == MutationScratchState.ABSENT else isPresent(expectedHash)

internal fun SecureScratchEntryObservation.isPresent(expectedHash: ExactFileImageSha256?): Boolean =
    expectedHash != null &&
        state == MutationScratchState.PRESENT &&
        sha256 == expectedHash &&
        identity != null

internal fun recoveryBlocked(
    target: Path,
    retainedPaths: List<Path>,
    failure: Throwable? = null,
    restorationFailure: Throwable? = null,
): UnsafeWorkspaceMutationException =
    UnsafeWorkspaceMutationException(
        message = "Mutation scratch recovery could not prove a journal-authorized final state",
        details = buildMap {
            val paths = retainedPaths.distinct().sorted()
            put("filePath", target.toString())
            put("recoveryFilePathCount", paths.size.toString())
            paths.forEachIndexed { index, path -> put("recoveryFilePath.$index", path.toString()) }
            failure?.let {
                put("cause", it.failureReason())
                if (it is UnsafeWorkspaceMutationException) {
                    it.details.forEach { (key, value) -> put("cause.$key", value) }
                }
            }
            restorationFailure?.let { put("restorationFailure", it.failureReason()) }
        },
    )
