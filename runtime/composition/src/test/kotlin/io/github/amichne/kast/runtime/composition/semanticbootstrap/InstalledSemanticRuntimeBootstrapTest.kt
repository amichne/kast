package io.github.amichne.kast.runtime.composition.semanticbootstrap

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeAssemblyFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeStateDirectoryFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeWorkspaceFailure
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstalledSemanticRuntimeBootstrapTest {
    @Test
    fun `one attempt projects project JVM rejection into its exact document`() {
        val rawAttempt = "123e4567-e89b-42d3-a456-426614174000"
        val attempt = (
            InstalledSemanticRuntimeBootstrapAttempt.admit(rawAttempt) as
                InstalledSemanticRuntimeBootstrapAttemptAdmission.Admitted
            ).attempt
        val runtimeFailure = InstalledKastRuntimeFailure.Assembly(
            InstalledRuntimeAssemblyFailure.WorkspacePublication(
                InstalledRuntimeWorkspaceFailure.IntellijBootstrap(
                    InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
                ),
            ),
        )
        val projected = attempt.rejectionDocument(setOf(runtimeFailure)) as
            InstalledSemanticRuntimeBootstrapRejection.Projected
        val attemptId = (SemanticRuntimeBootstrapAttemptId.admit(rawAttempt) as
            Refinement.Refined).value

        assertEquals(
            Refinement.Refined(
                SemanticRuntimeBootstrapState.Rejected(
                    attemptId,
                    SemanticRuntimeBootstrapFailure.PROJECT_JVM_UNAVAILABLE,
                ),
            ),
            SemanticRuntimeBootstrapCodec.decode(projected.document.boundaryValue()),
        )
    }

    @Test
    fun `project JVM rejection mixed with an unrelated cause remains ambiguous`() {
        val attempt = (
            InstalledSemanticRuntimeBootstrapAttempt.admit(
                "123e4567-e89b-42d3-a456-426614174000",
            ) as InstalledSemanticRuntimeBootstrapAttemptAdmission.Admitted
            ).attempt
        val projectJvm = InstalledKastRuntimeFailure.Assembly(
            InstalledRuntimeAssemblyFailure.WorkspacePublication(
                InstalledRuntimeWorkspaceFailure.IntellijBootstrap(
                    InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
                ),
            ),
        )
        val stateDirectory = InstalledKastRuntimeFailure.StateDirectory(
            InstalledRuntimeStateDirectoryFailure.UNAVAILABLE,
        )

        assertEquals(
            InstalledSemanticRuntimeBootstrapRejection.Ambiguous,
            attempt.rejectionDocument(setOf(projectJvm, stateDirectory)),
        )
    }

    @Test
    fun `invalid Gradle JVM configuration projects into its exact document`() {
        val rawAttempt = "123e4567-e89b-42d3-a456-426614174000"
        val attempt = (
            InstalledSemanticRuntimeBootstrapAttempt.admit(rawAttempt) as
                InstalledSemanticRuntimeBootstrapAttemptAdmission.Admitted
            ).attempt
        val runtimeFailure = InstalledKastRuntimeFailure.Assembly(
            InstalledRuntimeAssemblyFailure.WorkspacePublication(
                InstalledRuntimeWorkspaceFailure.IntellijBootstrap(
                    InstalledIntellijWorkspaceFailure.GRADLE_JVM_CONFIGURATION_INVALID,
                ),
            ),
        )
        val projected = attempt.rejectionDocument(setOf(runtimeFailure)) as
            InstalledSemanticRuntimeBootstrapRejection.Projected
        val attemptId = (SemanticRuntimeBootstrapAttemptId.admit(rawAttempt) as
            Refinement.Refined).value

        assertEquals(
            Refinement.Refined(
                SemanticRuntimeBootstrapState.Rejected(
                    attemptId,
                    SemanticRuntimeBootstrapFailure.GRADLE_JVM_CONFIGURATION_INVALID,
                ),
            ),
            SemanticRuntimeBootstrapCodec.decode(projected.document.boundaryValue()),
        )
    }

    @Test
    fun `platform linkage rejection projects into its exact document`() {
        val rawAttempt = "123e4567-e89b-42d3-a456-426614174000"
        val attempt = (
            InstalledSemanticRuntimeBootstrapAttempt.admit(rawAttempt) as
                InstalledSemanticRuntimeBootstrapAttemptAdmission.Admitted
            ).attempt
        val runtimeFailure = InstalledKastRuntimeFailure.Assembly(
            InstalledRuntimeAssemblyFailure.WorkspacePublication(
                InstalledRuntimeWorkspaceFailure.IntellijBootstrap(
                    InstalledIntellijWorkspaceFailure.PLATFORM_LINKAGE_INVALID,
                ),
            ),
        )
        val projected = attempt.rejectionDocument(setOf(runtimeFailure)) as
            InstalledSemanticRuntimeBootstrapRejection.Projected
        val attemptId = (SemanticRuntimeBootstrapAttemptId.admit(rawAttempt) as
            Refinement.Refined).value

        assertEquals(
            Refinement.Refined(
                SemanticRuntimeBootstrapState.Rejected(
                    attemptId,
                    SemanticRuntimeBootstrapFailure.PLATFORM_LINKAGE_INVALID,
                ),
            ),
            SemanticRuntimeBootstrapCodec.decode(projected.document.boundaryValue()),
        )
    }
}
