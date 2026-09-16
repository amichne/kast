package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class WorkspaceSelectionFailure {
    PATH_REJECTED,
    REGISTRY_REJECTED,
    REGISTRATION_PATH_REJECTED,
    REGISTRATION_DOCUMENT_REJECTED,
    REGISTRATION_WRITE_REJECTED,
    REGISTRATION_WORKSPACE_CONFLICT,
    REGISTRATION_CAPACITY_EXCEEDED,
    UNREGISTERED,
    AMBIGUOUS,
    WORKING_DIRECTORY_OUTSIDE_ROOT,
}

/** Registration is a thread-creation effect; binding validation continues to use read-only select. */
internal fun WorkspaceEnrollment.selectForStart(
    raw: String?,
    explicitRoot: String? = null,
    observe: (WorkspaceStartupObservation) -> Unit = ::reportRegistration,
): WorkspaceSelection {
    val selected = select(raw, explicitRoot)
    if (
        this !is WorkspaceEnrollment.Registered ||
            selected !is WorkspaceSelection.Rejected ||
            selected.failure != WorkspaceSelectionFailure.UNREGISTERED
    )
        return selected
    val cwd = startupDirectory(raw) ?: return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.PATH_REJECTED)
    val root =
        if (explicitRoot == null) cwd
        else
            startupDirectory(explicitRoot)
                ?: return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.PATH_REJECTED)
    if (!cwd.path.startsWith(root.path))
        return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.WORKING_DIRECTORY_OUTSIDE_ROOT)
    val registration = register(root)
    observe(
        WorkspaceStartupObservation(
            outcome =
                when (registration) {
                    is Refinement.Refined -> WorkspaceStartupOutcome.Registered
                    is Refinement.Rejected -> WorkspaceStartupOutcome.Rejected(registration.failure)
                }
        )
    )
    return when (val registered = registration) {
        is Refinement.Refined -> select(raw, explicitRoot)
        is Refinement.Rejected ->
            WorkspaceSelection.Rejected(
                when (registered.failure) {
                    EnrollmentFailure.PATH_REJECTED -> WorkspaceSelectionFailure.REGISTRATION_PATH_REJECTED
                    EnrollmentFailure.DOCUMENT_REJECTED -> WorkspaceSelectionFailure.REGISTRATION_DOCUMENT_REJECTED
                    EnrollmentFailure.WRITE_REJECTED -> WorkspaceSelectionFailure.REGISTRATION_WRITE_REJECTED
                    EnrollmentFailure.WORKSPACE_CONFLICT -> WorkspaceSelectionFailure.REGISTRATION_WORKSPACE_CONFLICT
                    EnrollmentFailure.CAPACITY_EXCEEDED -> WorkspaceSelectionFailure.REGISTRATION_CAPACITY_EXCEEDED
                }
            )
    }
}

private fun startupDirectory(raw: String?): CanonicalBrokerDirectory? =
    try {
        raw?.let(Path::of)?.takeIf(Path::isAbsolute)?.toRealPath()?.let(CanonicalBrokerDirectory::admit)
    } catch (_: java.io.IOException) {
        null
    } catch (_: java.nio.file.InvalidPathException) {
        null
    }

@Serializable
internal sealed interface WorkspaceStartupOutcome {
    @Serializable @SerialName("registered") data object Registered : WorkspaceStartupOutcome

    @Serializable @SerialName("rejected") data class Rejected(val failure: EnrollmentFailure) : WorkspaceStartupOutcome
}

@Serializable
internal data class WorkspaceStartupObservation(
    val event: String = "kast_workspace_registration",
    val outcome: WorkspaceStartupOutcome,
)

private val startupJson = Json { encodeDefaults = true }

private fun reportRegistration(observation: WorkspaceStartupObservation) {
    System.err.println(startupJson.encodeToString(WorkspaceStartupObservation.serializer(), observation))
}
