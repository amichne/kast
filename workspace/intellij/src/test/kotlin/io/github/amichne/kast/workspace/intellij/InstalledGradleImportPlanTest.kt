package io.github.amichne.kast.workspace.intellij

import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstalledGradleImportPlanTest {
    @Test
    fun `linked exact workspace requests one callback-backed refresh`() {
        val linkedSettings = GradleProjectSettings("/workspace").apply {
            gradleJvm = "prior-jvm"
        }
        val gradleJvm = admittedGradleJvm()

        val application = assertInstanceOf(
            InstalledGradleImportApplication.Applied::class.java,
            InstalledGradleLinkPresence.Linked(linkedSettings).applyImportJvm(gradleJvm),
        )

        assertInstanceOf(
            InstalledGradleImportOperation.RefreshLinked::class.java,
            application.operation,
        )
        assertEquals(gradleJvm.projectSettingsSelector(), linkedSettings.gradleJvm)
        assertEquals(InstalledGradleImportRollback.RolledBack, application.rollback())
        assertEquals("prior-jvm", linkedSettings.gradleJvm)
    }

    @Test
    fun `unlinked exact workspace is linked`() {
        val application = assertInstanceOf(
            InstalledGradleImportApplication.Applied::class.java,
            InstalledGradleLinkPresence.Unlinked.applyImportJvm(admittedGradleJvm()),
        )
        assertInstanceOf(InstalledGradleImportOperation.LinkUnlinked::class.java, application.operation)
        assertEquals(InstalledGradleImportRollback.RolledBack, application.rollback())
    }

    private fun admittedGradleJvm(): InstalledGradleJvm {
        val javaHome = System.getProperty("java.home")
        return when (val admission = InstalledGradleJvm.admit(javaHome, javaHome)) {
            is InstalledGradleJvmAdmission.Admitted -> admission.jvm
            is InstalledGradleJvmAdmission.Rejected -> error(admission.failure)
        }
    }
}
