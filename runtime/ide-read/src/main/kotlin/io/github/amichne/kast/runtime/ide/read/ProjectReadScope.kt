package io.github.amichne.kast.runtime.ide.read

import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability

/** Bound exact-root and epoch-source identity for one single-flight controller. */
internal class ProjectReadScope private constructor(
    private val canonicalRoot: CanonicalWorkspaceRoot,
    private val comparisonEpoch: ProjectReadEpoch<*>,
) {
    /**
     * Proof transition: `VfsPassiveReadCapability -> ProjectReadScopeAdmission`.
     *
     * Establishes exact root and comparable source, or returns one closed scope failure.
     */
    fun admit(freshness: VfsPassiveReadCapability): ProjectReadScopeAdmission = when {
        freshness.canonicalRoot != canonicalRoot -> ProjectReadScopeAdmission.Rejected(
            ProjectReadAdmissionFailure.WrongProject,
        )
        comparisonEpoch.relationTo(freshness.admittedEpoch) ==
            ProjectReadEpochRelation.INCOMPARABLE -> ProjectReadScopeAdmission.Rejected(
                ProjectReadAdmissionFailure.IncomparableProjectSource,
            )
        else -> ProjectReadScopeAdmission.Admitted(freshness)
    }

    companion object {
        /**
         * Proof transition: `VfsPassiveReadCapability -> ProjectReadScope`.
         *
         * Retains exact root and epoch domain. Raw evidence cannot leave this controller.
         */
        fun bind(freshness: VfsPassiveReadCapability): ProjectReadScope = ProjectReadScope(
            freshness.canonicalRoot,
            freshness.admittedEpoch,
        )
    }
}

/** Closed refinement of freshness evidence into one controller's project scope. */
internal sealed interface ProjectReadScopeAdmission {
    class Admitted(val freshness: VfsPassiveReadCapability) : ProjectReadScopeAdmission
    class Rejected(val failure: ProjectReadAdmissionFailure) : ProjectReadScopeAdmission
}
