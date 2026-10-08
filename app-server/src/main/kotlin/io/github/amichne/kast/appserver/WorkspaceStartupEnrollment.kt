package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class WorkspaceSelectionFailure {
    PATH_REJECTED,
    START_UNAVAILABLE,
    START_NOT_DIRECTORY,
    ROOT_MARKER_NOT_FOUND,
    INVALID_ROOT_MARKER,
    ROOT_SELECTION_REJECTED,
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
    if (this !is WorkspaceEnrollment.Registered) return select(raw, explicitRoot)
    val cwd = startupDirectory(raw) ?: return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.PATH_REJECTED)
    val root =
        when (val discovered = startupRoot(cwd, explicitRoot)) {
            is Refinement.Refined -> discovered.value
            is Refinement.Rejected -> return WorkspaceSelection.Rejected(discovered.failure)
        }
    // An older broad registration must never override the settings owner of a new thread.
    val selected = select(raw, root.path.toString())
    if (selected !is WorkspaceSelection.Rejected || selected.failure != WorkspaceSelectionFailure.UNREGISTERED)
        return selected
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
        is Refinement.Refined -> select(raw, root.path.toString())
        is Refinement.Rejected -> WorkspaceSelection.Rejected(registered.failure.selectionFailure())
    }
}

private fun startupRoot(
    cwd: CanonicalBrokerDirectory,
    explicitRoot: String?,
): Refinement<CanonicalRoot, WorkspaceSelectionFailure> {
    val requested = explicitRoot?.let {
        startupDirectory(it) ?: return Refinement.Rejected(WorkspaceSelectionFailure.PATH_REJECTED)
    }
    if (requested != null && !cwd.path.startsWith(requested.path))
        return Refinement.Rejected(WorkspaceSelectionFailure.WORKING_DIRECTORY_OUTSIDE_ROOT)
    val root =
        when (val discovered = FilesystemCanonicalRootDiscovery.discover(requested?.path ?: cwd.path)) {
            is CanonicalRootDiscovery.Discovered -> discovered.root
            is CanonicalRootDiscovery.Rejected -> return Refinement.Rejected(discovered.failure.selectionFailure())
        }
    if (requested != null && root.path != requested.path)
        return Refinement.Rejected(WorkspaceSelectionFailure.ROOT_SELECTION_REJECTED)
    return Refinement.Refined(root)
}

private fun CanonicalRootFailure.selectionFailure(): WorkspaceSelectionFailure =
    when (this) {
        CanonicalRootFailure.START_UNAVAILABLE -> WorkspaceSelectionFailure.START_UNAVAILABLE
        CanonicalRootFailure.START_NOT_DIRECTORY -> WorkspaceSelectionFailure.START_NOT_DIRECTORY
        CanonicalRootFailure.ROOT_MARKER_NOT_FOUND -> WorkspaceSelectionFailure.ROOT_MARKER_NOT_FOUND
        CanonicalRootFailure.INVALID_ROOT_MARKER -> WorkspaceSelectionFailure.INVALID_ROOT_MARKER
    }

private fun EnrollmentFailure.selectionFailure(): WorkspaceSelectionFailure =
    when (this) {
        EnrollmentFailure.PATH_REJECTED -> WorkspaceSelectionFailure.REGISTRATION_PATH_REJECTED
        EnrollmentFailure.DOCUMENT_REJECTED -> WorkspaceSelectionFailure.REGISTRATION_DOCUMENT_REJECTED
        EnrollmentFailure.WRITE_REJECTED -> WorkspaceSelectionFailure.REGISTRATION_WRITE_REJECTED
        EnrollmentFailure.WORKSPACE_CONFLICT -> WorkspaceSelectionFailure.REGISTRATION_WORKSPACE_CONFLICT
        EnrollmentFailure.CAPACITY_EXCEEDED -> WorkspaceSelectionFailure.REGISTRATION_CAPACITY_EXCEEDED
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
