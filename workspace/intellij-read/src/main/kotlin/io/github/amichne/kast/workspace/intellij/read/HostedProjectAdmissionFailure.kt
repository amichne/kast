package io.github.amichne.kast.workspace.intellij.read

/** Shared finite rejection carried by capability-specific hosted physical admissions. */
sealed interface HostedProjectAdmissionFailure {
    data class ProjectRejected(
        val cause: ExistingProjectAdmissionFailure,
    ) : HostedProjectAdmissionFailure
}
