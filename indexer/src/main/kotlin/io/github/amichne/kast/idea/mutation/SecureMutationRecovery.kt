package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import java.nio.file.Path
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
        val effectiveRestoration = restoration ?: restoreDetached(parent, fileName, detached, target, platform)
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
    ): CleanupResult = removeExactName(
        parent = parent,
        sourceName = entry.name,
        expectedIdentity = entry.status.identity,
        target = target,
        api = api,
        platform = platform,
    )

internal fun SecureWorkspaceMutation.releaseFinalReservation(
        parent: NativeDescriptor,
        fileName: String,
        reservation: PreparedFile,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
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
            )
        } catch (exception: Exception) {
            return FinalReservationRelease.Blocked(
                entryRecoveryFilePath = targetPath,
                restoredToFinalName = true,
                reason = exception.failureReason(),
            )
        }
        val cleanupPath = target.parent.resolve(cleanupName)
        fun blocked(reason: String): FinalReservationRelease.Blocked {
            val recoveryPath = restoreCleanupName(parent, fileName, cleanupName, target, platform)
            return FinalReservationRelease.Blocked(
                entryRecoveryFilePath = recoveryPath,
                restoredToFinalName = recoveryPath == targetPath,
                reason = reason,
            )
        }
        fun releasedWithRecovery(reason: String): FinalReservationRelease.Released =
            FinalReservationRelease.Released(
                CleanupResult.Retained(
                    recoveryFilePath = cleanupPath,
                    reason = reason,
                ),
            )

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
            return releasedWithRecovery(exception.failureReason())
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

        if (api.unlinkat(parent.value, cleanupName, 0) < 0) {
            return releasedWithRecovery("unlinkat-delete-reservation failed with errno ${Native.getLastError()}")
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
            )
        } catch (exception: Exception) {
            return CleanupResult.Retained(
                recoveryFilePath = sourcePath,
                reason = exception.failureReason(),
            )
        }
        val cleanupPath = target.parent.resolve(cleanupName)
        fun retained(reason: String): CleanupResult.Retained = CleanupResult.Retained(
            recoveryFilePath = restoreCleanupName(parent, sourceName, cleanupName, target, platform),
            reason = reason,
        )

        val cleanupDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (cleanupDescriptorValue < 0) {
            val errno = Native.getLastError()
            return retained("openat-cleanup failed with errno $errno")
        }
        NativeDescriptor(api, cleanupDescriptorValue).use { cleanupDescriptor ->
            val cleanupStatus = try {
                descriptorStatus(api, platform, cleanupDescriptor.value, target)
            } catch (exception: Exception) {
                return retained(exception.failureReason())
            }
            if (cleanupStatus.identity != expectedIdentity) {
                return retained("cleanup identity did not match the descriptor-validated entry")
            }
        }

        try {
            beforeCleanupUnlink(cleanupPath)
        } catch (exception: Exception) {
            return retained(exception.failureReason())
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
                return retained(exception.failureReason())
            }
            if (finalStatus.identity != expectedIdentity) {
                return retained("cleanup identity changed before unlink")
            }
        }

        if (api.unlinkat(parent.value, cleanupName, 0) < 0) {
            val errno = Native.getLastError()
            return retained("unlinkat-cleanup failed with errno $errno")
        }
        return CleanupResult.Removed
    }

internal fun SecureWorkspaceMutation.restoreCleanupName(
        parent: NativeDescriptor,
        sourceName: String,
        cleanupName: String,
        target: Path,
        platform: PosixPlatform,
    ): Path {
        val sourcePath = target.parent.resolve(sourceName)
        val cleanupPath = target.parent.resolve(cleanupName)
        return try {
            when (
                renameNoReplace(
                    parent = parent,
                    sourceName = cleanupName,
                    destinationName = sourceName,
                    target = target,
                    platform = platform,
                    phase = SecureWorkspaceRenamePhase.RESTORE_CLEANUP,
                )
            ) {
                RenameNoReplaceOutcome.MOVED -> sourcePath
                RenameNoReplaceOutcome.DESTINATION_EXISTS, RenameNoReplaceOutcome.SOURCE_MISSING -> cleanupPath
            }
        } catch (_: Exception) {
            cleanupPath
        }
    }

internal fun SecureWorkspaceMutation.preCommitFailure(
        target: Path,
        operation: String,
        cause: Exception,
        cleanup: CleanupResult,
    ): UnsafeWorkspaceMutationException = UnsafeWorkspaceMutationException(
        message = "Secure workspace mutation failed before commit",
        details = failureDetails(target, operation) +
            mapOf("cause" to cause.failureReason()) +
            cleanup.conflictDetails(),
    )

internal fun Throwable.failureReason(): String = message ?: this::class.java.simpleName
