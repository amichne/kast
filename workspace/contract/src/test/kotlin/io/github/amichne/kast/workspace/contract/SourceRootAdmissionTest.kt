package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceRootAdmissionTest {
    @Test
    fun `platform-invalid source and build paths remain typed admission failures`() {
        val valid = GradleSourceRootEvidence(
            ideaModuleName = "root.main",
            workspaceRelativeBuildRoot = ".",
            gradleProjectPath = ":",
            sourceSetName = "main",
            workspaceRelativeSourceRoot = "src/main/kotlin",
            provenance = SourceRootProvenance.Authored,
        )
        val invalid = "src" + 0.toChar() + "/main"

        assertEquals(
            Refinement.Rejected(setOf(SourceRootAdmissionFailure.InvalidSourceRoot)),
            SourceRoot.admit(valid.copy(workspaceRelativeSourceRoot = invalid)),
        )
        assertEquals(
            Refinement.Rejected(setOf(SourceRootAdmissionFailure.InvalidLinkedBuildRoot)),
            SourceRoot.admit(valid.copy(workspaceRelativeBuildRoot = invalid)),
        )
    }
}
