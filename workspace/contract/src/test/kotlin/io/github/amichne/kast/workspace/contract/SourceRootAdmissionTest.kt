package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceRootAdmissionTest {
    @Test
    fun `source-set names retain exact spelling and reject blank boundary data`() {
        val raw =
            GradleSourceRootEvidence(
                ideaModuleName = "root.custom",
                workspaceRelativeBuildRoot = ".",
                gradleProjectPath = ":",
                sourceSetName = " customMain ",
                workspaceRelativeSourceRoot = "unrelated/path",
                provenance = SourceRootProvenance.Authored,
            )
        val admitted = SourceRoot.admit(raw) as Refinement.Refined
        assertEquals(raw.sourceSetName, admitted.value.owner.sourceSet.value)
        assertEquals(
            Refinement.Rejected(setOf(SourceRootAdmissionFailure.InvalidSourceSetName)),
            SourceRoot.admit(raw.copy(sourceSetName = " ")),
        )
    }

    @Test
    fun `platform-invalid source and build paths remain typed admission failures`() {
        val valid =
            GradleSourceRootEvidence(
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
