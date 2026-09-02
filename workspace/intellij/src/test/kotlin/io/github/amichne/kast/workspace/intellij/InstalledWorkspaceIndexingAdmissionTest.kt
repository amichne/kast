package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstalledWorkspaceIndexingAdmissionTest {
    @Test
    fun `platform linkage failure is retained as an exact indexing rejection`() {
        assertEquals(
            InstalledIndexingPlatformObservation.Rejected(
                InstalledIndexingReadinessFailure.PlatformLinkageInvalid,
            ),
            observeInstalledIndexingPlatform<Unit> { throw LinkageError("duplicate fixture") },
        )
        assertEquals(
            InstalledWorkspaceIndexingAdmission.Rejected(
                InstalledIntellijWorkspaceFailure.PLATFORM_LINKAGE_INVALID,
            ),
            InstalledIndexingReadiness.Rejected(
                InstalledIndexingReadinessFailure.PlatformLinkageInvalid,
            ).workspaceOpeningAdmission(),
        )
    }

    @Test
    fun `only project JVM readiness failure becomes project JVM unavailable`() {
        assertEquals(
            InstalledWorkspaceIndexingAdmission.Rejected(
                InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
            ),
            InstalledIndexingReadiness.Rejected(
                InstalledIndexingReadinessFailure.ProjectJvmUnavailable,
            ).workspaceOpeningAdmission(),
        )
    }

    @Test
    fun `non JVM indexing failures remain startup failures`() {
        listOf(
            InstalledIndexingReadinessFailure.IndexingTimedOut,
            InstalledIndexingReadinessFailure.ModuleMaterializationUnavailable,
            InstalledIndexingReadinessFailure.PlatformObservationUnavailable,
            InstalledIndexingReadinessFailure.ProjectDisposed,
        ).forEach { failure ->
            assertEquals(
                InstalledWorkspaceIndexingAdmission.Rejected(
                    InstalledIntellijWorkspaceFailure.STARTUP_FAILED,
                ),
                InstalledIndexingReadiness.Rejected(failure).workspaceOpeningAdmission(),
            )
        }
    }

    @Test
    fun `ready and interrupted indexing remain distinct opening outcomes`() {
        assertEquals(
            InstalledWorkspaceIndexingAdmission.Ready,
            InstalledIndexingReadiness.Ready.workspaceOpeningAdmission(),
        )
        assertEquals(
            InstalledWorkspaceIndexingAdmission.Rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            ),
            InstalledIndexingReadiness.Rejected(
                InstalledIndexingReadinessFailure.Interrupted,
            ).workspaceOpeningAdmission(),
        )
    }
}
