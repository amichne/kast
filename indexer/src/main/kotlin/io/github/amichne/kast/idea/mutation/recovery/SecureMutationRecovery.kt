package io.github.amichne.kast.idea.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*

internal fun Exception.rollbackDetails(): Map<String, String> =
        mapOf("cause" to failureReason()) + when (this) {
            is UnsafeWorkspaceMutationException -> details.mapKeys { (key, _) -> "cause.$key" }
            else -> emptyMap()
        }

internal fun SecureWorkspaceMutation.conflictWithRestoration(
        message: String,
        target: Path,
        fileName: String,
        detached: DetachedTarget,
        parent: NativeDescriptor,
        platform: PosixPlatform,
        details: Map<String, String> = emptyMap(),
        restoration: Restoration? = null,
        cleanup: CleanupResult = CleanupResult.Removed,
    ): ConflictException {
        val primaryConflict = ConflictException(
            message = message,
            details = mapOf("filePath" to target.toString()) + details + detached.status.identity.details(),
        )
        val effectiveRestoration = restoration ?: try {
            restoreDetached(parent, fileName, detached, target, platform)
        } catch (restorationFailure: Exception) {
            val evidence = UnsafeWorkspaceMutationException(
                message = "The detached workspace entry could not be restored after a conflict",
                details = failureDetails(target, "conflict-restoration") +
                    mapOf(
                        "cause" to primaryConflict.failureReason(),
                        "restorationFailure" to restorationFailure.failureReason(),
                        "recoveryFilePath" to target.parent.resolve(detached.name).toString(),
                    ) +
                    primaryConflict.details.mapKeys { (key, _) -> "cause.$key" } +
                    detached.status.identity.details(),
            )
            restorationFailure.rethrowIfMutationCancellation(evidence)
            evidence.initCause(primaryConflict)
            evidence.addSuppressed(restorationFailure)
            throw evidence
        }
        val restorationDetails = when (effectiveRestoration) {
            Restoration.RESTORED -> mapOf("restoration" to "restored")
            Restoration.QUARANTINED -> mapOf(
                "restoration" to "quarantined",
                "recoveryFilePath" to target.parent.resolve(detached.name).toString(),
            )
        }
        return ConflictException(
            message = message,
            details = mapOf("filePath" to target.toString()) +
                details +
                restorationDetails +
                cleanup.conflictDetails() +
                detached.status.identity.details(),
        )
    }

internal fun SecureWorkspaceMutation.restoreDetached(
        parent: NativeDescriptor,
        fileName: String,
        detached: DetachedTarget,
        target: Path,
        platform: PosixPlatform,
    ): Restoration = when (
        renameNoReplace(
            parent = parent,
            sourceName = detached.name,
            destinationName = fileName,
            target = target,
            platform = platform,
            phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
        )
    ) {
        RenameNoReplaceOutcome.MOVED -> {
            Restoration.RESTORED
        }

        RenameNoReplaceOutcome.DESTINATION_EXISTS -> Restoration.QUARANTINED
        RenameNoReplaceOutcome.SOURCE_MISSING -> throw UnsafeWorkspaceMutationException(
            message = "The detached workspace entry disappeared before it could be restored",
            details = failureDetails(target, "restore-detached-source-missing") +
                mapOf("recoveryFilePath" to target.parent.resolve(detached.name).toString()) +
                detached.status.identity.details(),
        )
    }

internal fun SecureWorkspaceMutation.restoreUnopenedQuarantine(
        parent: NativeDescriptor,
        fileName: String,
        quarantineName: String,
        target: Path,
        platform: PosixPlatform,
    ) {
        when (
            renameNoReplace(
                parent = parent,
                sourceName = quarantineName,
                destinationName = fileName,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
            )
        ) {
            RenameNoReplaceOutcome.MOVED -> Unit
            RenameNoReplaceOutcome.DESTINATION_EXISTS -> throw UnsafeWorkspaceMutationException(
                message = "The detached workspace entry could not be restored because the target name is occupied",
                details = failureDetails(target, "restore-unopened-quarantine") + mapOf(
                    "recoveryFilePath" to target.parent.resolve(quarantineName).toString(),
                ),
            )

            RenameNoReplaceOutcome.SOURCE_MISSING -> throw UnsafeWorkspaceMutationException(
                message = "The detached workspace entry disappeared before it could be opened",
                details = failureDetails(target, "restore-unopened-quarantine-source-missing"),
            )
        }
    }

internal fun SecureWorkspaceMutation.removeExactNamedEntry(
        parent: NativeDescriptor,
        entry: ExactNamedEntry,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
        cleanupName: String? = null,
        expectedSha256: ExactFileImageSha256? = null,
    ): CleanupResult = removeExactName(
        parent = parent,
        sourceName = entry.name,
        expectedIdentity = entry.status.identity,
        target = target,
        api = api,
        platform = platform,
        exactCleanupName = cleanupName,
        expectedSha256 = expectedSha256,
    )

internal fun SecureWorkspaceMutation.releaseFinalReservation(
        parent: NativeDescriptor,
        fileName: String,
        reservation: PreparedFile,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
        exactCleanupName: String? = null,
    ): FinalReservationRelease {
        val targetPath = target.parent.resolve(fileName)
        val cleanupName = try {
            moveToUniqueName(
                parent = parent,
                sourceName = fileName,
                prefix = CLEANUP_PREFIX,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.MOVE_CLEANUP,
                sourceMissing = {
                    throw UnsafeWorkspaceMutationException(
                        message = "The deletion reservation disappeared before final-name release",
                        details = failureDetails(target, "delete-reservation-source-missing"),
                    )
                },
                exactDestinationName = exactCleanupName,
            )
        } catch (exception: Exception) {
            exception.rethrowIfParentDirectoryDurabilityFailed()
            if (exception is ProcessCanceledException || exception is CancellationException) {
                return FinalReservationRelease.Cancelled(targetPath, exception)
            }
            return FinalReservationRelease.Blocked(
                entryRecoveryFilePath = targetPath,
                restoredToFinalName = true,
                reason = exception.failureReason(),
            )
        }
        val cleanupPath = target.parent.resolve(cleanupName)
        fun blocked(reason: String): FinalReservationRelease.Blocked {
            val restoration = restoreCleanupName(parent, fileName, cleanupName, target, platform)
            val restorationReason = when (restoration) {
                is CleanupNameRestoration.Restored -> reason
                is CleanupNameRestoration.Retained ->
                    "$reason; cleanup restoration failed: ${restoration.failure.failureReason()}"
            }
            return FinalReservationRelease.Blocked(
                entryRecoveryFilePath = restoration.recoveryFilePath,
                restoredToFinalName = restoration.recoveryFilePath == targetPath,
                reason = restorationReason,
            )
        }
        fun releasedWithRecovery(reason: String): FinalReservationRelease.Released =
            FinalReservationRelease.Released(
                CleanupResult.Retained(
                    recoveryFilePath = cleanupPath,
                    reason = reason,
                ),
            )
        fun releasedWithRecovery(failure: Exception): FinalReservationRelease.Released =
            if (failure is ProcessCanceledException || failure is CancellationException) {
                FinalReservationRelease.Released(CleanupResult.Cancelled.of(cleanupPath, failure))
            } else {
                releasedWithRecovery(failure.failureReason())
            }

        val cleanupDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (cleanupDescriptorValue < 0) {
            return blocked("openat-delete-reservation failed with errno ${Native.getLastError()}")
        }
        NativeDescriptor(api, cleanupDescriptorValue).use { cleanupDescriptor ->
            val cleanupStatus = try {
                descriptorStatus(api, platform, cleanupDescriptor.value, target)
            } catch (exception: Exception) {
                return blocked(exception.failureReason())
            }
            if (cleanupStatus.identity != reservation.status.identity) {
                return blocked("delete reservation identity changed before final-name release")
            }
        }

        try {
            beforeCleanupUnlink(cleanupPath)
        } catch (exception: Exception) {
            return releasedWithRecovery(exception)
        }

        val finalDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (finalDescriptorValue < 0) {
            return blocked("openat-delete-reservation-recheck failed with errno ${Native.getLastError()}")
        }
        NativeDescriptor(api, finalDescriptorValue).use { finalDescriptor ->
            val finalStatus = try {
                descriptorStatus(api, platform, finalDescriptor.value, target)
            } catch (exception: Exception) {
                return blocked(exception.failureReason())
            }
            if (finalStatus.identity != reservation.status.identity) {
                return blocked("delete reservation identity changed immediately before unlink")
            }
        }

        when (val unlink = unlinkAndPersistParent(parent, cleanupName, target)) {
            NamespaceUnlinkResult.Removed -> Unit
            is NamespaceUnlinkResult.Failed -> {
                return releasedWithRecovery("unlinkat-delete-reservation failed with errno ${unlink.errno}")
            }
        }
        return FinalReservationRelease.Released(CleanupResult.Removed)
    }

internal fun SecureWorkspaceMutation.removeExactName(
        parent: NativeDescriptor,
        sourceName: String,
        expectedIdentity: NativeFileIdentity,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
        exactCleanupName: String? = null,
        expectedSha256: ExactFileImageSha256? = null,
    ): CleanupResult {
        val sourcePath = target.parent.resolve(sourceName)
        val cleanupName = try {
            moveToUniqueName(
                parent = parent,
                sourceName = sourceName,
                prefix = CLEANUP_PREFIX,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.MOVE_CLEANUP,
                sourceMissing = {
                    throw UnsafeWorkspaceMutationException(
                        message = "The descriptor-identified workspace entry disappeared before cleanup",
                        details = failureDetails(target, "cleanup-source-missing") + expectedIdentity.details(),
                    )
                },
                exactDestinationName = exactCleanupName,
            )
        } catch (exception: Exception) {
            exception.rethrowIfParentDirectoryDurabilityFailed()
            return retainedCleanup(sourcePath, exception)
        }
        val cleanupPath = target.parent.resolve(cleanupName)
        fun retained(reason: String): CleanupResult.Retained =
            when (val restoration = restoreCleanupName(parent, sourceName, cleanupName, target, platform)) {
                is CleanupNameRestoration.Restored -> CleanupResult.Retained(
                    recoveryFilePath = restoration.recoveryFilePath,
                    reason = reason,
                )
                is CleanupNameRestoration.Retained -> CleanupResult.Retained.withRestorationFailure(
                    recoveryFilePath = restoration.recoveryFilePath,
                    primaryReason = reason,
                    restorationFailure = restoration.failure,
                )
            }
        fun retained(failure: Exception): CleanupResult {
            val restoration = try {
                restoreCleanupName(parent, sourceName, cleanupName, target, platform)
            } catch (restorationFailure: Exception) {
                restorationFailure.rethrowIfParentDirectoryDurabilityFailed()
                val evidence = UnsafeWorkspaceMutationException(
                    message = "Secure workspace cleanup retained recovery evidence after cancellation",
                    details = failureDetails(target, "cleanup-restoration-cancellation") + mapOf(
                        "recoveryFilePath" to cleanupPath.toString(),
                        "restorationFailure" to restorationFailure.failureReason(),
                    ),
                )
                if (failure is ProcessCanceledException || failure is CancellationException) {
                    failure.addSuppressed(evidence)
                    return retainedCleanup(cleanupPath, failure)
                }
                return retainedCleanup(cleanupPath, restorationFailure)
            }
            return when (restoration) {
                is CleanupNameRestoration.Restored -> retainedCleanup(restoration.recoveryFilePath, failure)
                is CleanupNameRestoration.Retained -> {
                    val evidence = UnsafeWorkspaceMutationException(
                        message = "Secure workspace cleanup retained recovery evidence after restoration failed",
                        details = failureDetails(target, "cleanup-restoration-failed") + mapOf(
                            "recoveryFilePath" to restoration.recoveryFilePath.toString(),
                            "restorationFailure" to restoration.failure.failureReason(),
                        ),
                    )
                    failure.rethrowIfMutationCancellation(evidence)
                    CleanupResult.Retained.withRestorationFailure(
                        recoveryFilePath = restoration.recoveryFilePath,
                        primaryReason = failure.failureReason(),
                        restorationFailure = restoration.failure,
                    )
                }
            }
        }

        val cleanupDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (cleanupDescriptorValue < 0) {
            val errno = Native.getLastError()
            return retained("openat-cleanup failed with errno $errno")
        }
        NativeDescriptor(api, cleanupDescriptorValue).use { cleanupDescriptor ->
            val cleanupStatus = try {
                descriptorStatus(api, platform, cleanupDescriptor.value, target)
            } catch (exception: Exception) {
                return retained(exception)
            }
            if (cleanupStatus.identity != expectedIdentity) {
                return retained("cleanup identity did not match the descriptor-validated entry")
            }
        }

        try {
            beforeCleanupUnlink(cleanupPath)
        } catch (exception: Exception) {
            return retained(exception)
        }

        val finalDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (finalDescriptorValue < 0) {
            val errno = Native.getLastError()
            return retained("openat-cleanup-recheck failed with errno $errno")
        }
        NativeDescriptor(api, finalDescriptorValue).use { finalDescriptor ->
            val finalStatus = try {
                descriptorStatus(api, platform, finalDescriptor.value, target)
            } catch (exception: Exception) {
                return retained(exception)
            }
            if (finalStatus.identity != expectedIdentity) {
                return retained("cleanup identity changed before unlink")
            }
            if (expectedSha256 != null) {
                val finalSha256 = try {
                    ExactFileImageSha256(FileHashing.sha256(readFullyBytes(api, finalDescriptor.value, target)))
                } catch (exception: Exception) {
                    return retained(exception)
                }
                if (finalSha256 != expectedSha256) {
                    return retained("cleanup bytes changed before unlink")
                }
            }
        }

        when (val unlink = unlinkAndPersistParent(parent, cleanupName, target)) {
            NamespaceUnlinkResult.Removed -> Unit
            is NamespaceUnlinkResult.Failed -> return retained("unlinkat-cleanup failed with errno ${unlink.errno}")
        }
        return CleanupResult.Removed
    }
