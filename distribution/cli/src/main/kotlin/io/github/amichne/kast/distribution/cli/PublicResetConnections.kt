package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal sealed interface PublicRecoverySelection {
    data object Absent : PublicRecoverySelection

    data class Selected(val file: PublicRecoveryFile) : PublicRecoverySelection

    data object Rejected : PublicRecoverySelection
}

internal class PublicRecoveryFile private constructor(val path: Path) {
    companion object {
        fun admit(root: Path, raw: String?): PublicRecoverySelection {
            if (raw == null) return PublicRecoverySelection.Absent
            val path =
                try {
                    Path.of(raw)
                } catch (_: IllegalArgumentException) {
                    return PublicRecoverySelection.Rejected
                }
            return if (path.isAbsolute && path.normalize() == path && !path.startsWith(root))
                PublicRecoverySelection.Selected(PublicRecoveryFile(path))
            else PublicRecoverySelection.Rejected
        }
    }
}

/** The bootstrap supplies an owned temporary file outside the installation being erased. */
internal fun savePublicResetConnections(root: Path, environment: Map<String, String>): LifecycleEffect =
    try {
        when (val selected = PublicRecoveryFile.admit(root, environment["KAST_PUBLIC_RESET_RECOVERY"])) {
            PublicRecoverySelection.Absent -> LifecycleEffect.Completed
            PublicRecoverySelection.Rejected -> connectionRecoveryRejected()
            is PublicRecoverySelection.Selected -> {
                when (val prior = readReceipt(root)) {
                    is ReceiptRead.Read -> writePublicResetRecovery(selected.file, prior.receipt)
                    is ReceiptRead.Unavailable -> LifecycleEffect.Completed
                }
            }
        }
    } catch (_: IOException) {
        connectionRecoveryRejected()
    } catch (_: SecurityException) {
        connectionRecoveryRejected()
    }

internal fun publicResetConnections(root: Path, environment: Map<String, String>): List<ManagedRegistration> {
    val file =
        when (val selected = PublicRecoveryFile.admit(root, environment["KAST_PUBLIC_RESET_RECOVERY"])) {
            PublicRecoverySelection.Absent -> return emptyList()
            PublicRecoverySelection.Rejected ->
                throw ManagementRejected("reset-connections", "recovery path is invalid")
            is PublicRecoverySelection.Selected -> selected.file
        }
    if (Files.notExists(file.path, NOFOLLOW_LINKS)) return emptyList()
    return when (val admitted = admitManagementReceipt(root, readBoundedFile(file.path, PUBLIC_RESET_RECEIPT_BYTES))) {
        is ReceiptRead.Read -> admitted.receipt.registrations
        is ReceiptRead.Unavailable -> throw ManagementRejected("reset-connections", admitted.reason)
    }
}

internal fun restorePublicResetConnections(root: Path, environment: Map<String, String>): LifecycleEffect {
    return try {
        val registrations = publicResetConnections(root, environment)
        if (registrations.isEmpty()) return LifecycleEffect.Completed
        val homeRaw = environment["HOME"] ?: return connectionRecoveryRejected()
        val home = Path.of(homeRaw)
        for (registration in registrations) restorePublicConnection(root, home, registration, environment)
        LifecycleEffect.Completed
    } catch (_: ManagementRejected) {
        connectionRecoveryRejected()
    } catch (_: IOException) {
        connectionRecoveryRejected()
    } catch (_: IllegalArgumentException) {
        connectionRecoveryRejected()
    } catch (_: SecurityException) {
        connectionRecoveryRejected()
    }
}

private fun restorePublicConnection(
    root: Path,
    home: Path,
    registration: ManagedRegistration,
    environment: Map<String, String>,
) {
    val directory =
        registration.directory?.let {
            when (val admission = ConnectionDirectory.admit(it)) {
                is ConnectionDirectoryAdmission.Admitted -> admission.directory
                is ConnectionDirectoryAdmission.Rejected ->
                    throw ManagementRejected("reset-connections", "connection directory is invalid")
            }
        }
    connectHarness(
        root,
        home,
        registration.connection,
        ownership = RegistrationOwnership.REPLACE_SELECTED_SLOT,
        directory = directory,
        environment = environment,
    )
}

private fun connectionRecoveryRejected() = LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)

private const val PUBLIC_RESET_RECEIPT_BYTES = 65536L
