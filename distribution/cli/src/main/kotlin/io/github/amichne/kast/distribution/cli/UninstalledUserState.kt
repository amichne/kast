package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Full uninstall owns only the closed, user-owned Kast state layout; upgrade never invokes this boundary. */
internal fun cleanupUninstalledUserState(
    home: Path,
    observe: (UninstallCleanupObservation) -> Unit,
    retire: () -> Unit = {},
) {
    observe(UninstallCleanupObservation.Started(UninstallArtifact.USER_STATE))
    val removal =
        when (val admission = UserStateInventory.admit(home)) {
            is UserStateAdmission.Rejected -> UserStateRemoval.Rejected(admission.failure)
            is UserStateAdmission.Admitted -> retireAndRemoveUserState(admission.inventory, retire)
        }
    when (removal) {
        UserStateRemoval.Completed -> observe(UninstallCleanupObservation.Completed(UninstallArtifact.USER_STATE))
        is UserStateRemoval.Rejected -> {
            observe(UninstallCleanupObservation.UserStateRejected(removal.failure))
            throw ManagementRejected("uninstall-user-state", removal.failure.message)
        }
    }
}

private fun retireAndRemoveUserState(
    inventory: UserStateInventory,
    retire: () -> Unit,
): UserStateRemoval =
    try {
        inventory.use {
            when (val validation = it.validateBeforeRetirement()) {
                UserStateRemoval.Completed -> {
                    retire()
                    it.remove()
                }
                is UserStateRemoval.Rejected -> validation
            }
        }
    } catch (_: java.io.IOException) {
        UserStateRemoval.Rejected(UserStateCleanupFailure.FILESYSTEM_REJECTED)
    }

@Serializable
internal enum class UserStateCleanupFailure(val message: String) {
    OWNERSHIP_UNPROVEN("user state ownership is unproven; retained cleanup receipt"),
    ACTIVE_HOST("IDE endpoint is still active; close IntelliJ IDEA and retry uninstall"),
    CAPACITY_EXCEEDED("user state inventory exceeds its bounded capacity; retained cleanup receipt"),
    FILESYSTEM_REJECTED("filesystem refused user state cleanup; retained cleanup receipt"),
    PATH_REJECTED("mutation state path is unproven; retained cleanup receipt"),
    SCHEMA_REJECTED("mutation state schema is unproven; retained cleanup receipt"),
    RECORD_REJECTED("mutation state records are unproven; retained cleanup receipt"),
    WORKSPACE_MISMATCH("mutation state belongs to another workspace; retained cleanup receipt"),
    UNSETTLED_MUTATION("mutation recovery remains unsettled; recover through the existing IDE owner before uninstall"),
    CHECKPOINT_REQUIRED(
        "mutation state has an uncheckpointed WAL; recover and close the existing IDE owner before uninstall"
    ),
    STORAGE_UNAVAILABLE("mutation state cannot be inspected; retained cleanup receipt"),
}

internal sealed interface UserStateAdmission {
    data class Admitted(val inventory: UserStateInventory) : UserStateAdmission

    data class Rejected(val failure: UserStateCleanupFailure) : UserStateAdmission
}

internal sealed interface UserStateRemoval {
    data object Completed : UserStateRemoval

    data class Rejected(val failure: UserStateCleanupFailure) : UserStateRemoval
}
