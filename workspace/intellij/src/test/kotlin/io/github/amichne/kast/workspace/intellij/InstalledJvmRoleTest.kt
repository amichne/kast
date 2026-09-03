package io.github.amichne.kast.workspace.intellij

import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InstalledJvmRoleTest {
    @Test
    fun `sidecar JVM establishes only the temporary bootstrap project JVM`() {
        val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
        val sidecar = when (
            val admission = InstalledSidecarJvm.admit(javaHome.toString(), javaHome.toString())
        ) {
            is InstalledSidecarJvmAdmission.Admitted -> admission.jvm
            is InstalledSidecarJvmAdmission.Rejected -> error(admission.failure)
        }

        val bootstrap = BootstrapProjectJvm.from(sidecar)

        assertEquals(javaHome, bootstrap.home)
    }

    @Test
    fun `Gradle settings require an independently selected JVM`() {
        val settings = GradleProjectSettings("/workspace").apply {
            gradleJvm = "prior-jvm"
        }
        val selected = selectedGradleJvmFixture("fixture-jdk-17")

        val application = assertInstanceOf(
            InstalledGradleImportApplication.Applied::class.java,
            InstalledGradleLinkPresence.Linked(settings).applyImportJvm(selected),
        )

        assertInstanceOf(
            InstalledGradleImportOperation.RefreshLinked::class.java,
            application.operation,
        )
        assertEquals("fixture-jdk-17", settings.gradleJvm)
    }

    private fun selectedGradleJvmFixture(selector: String): SelectedGradleJvm {
        val selection = assertInstanceOf(
            GradleJvmCandidateSelection.Selected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("7.6"),
                listOf(
                    GradleJvmCandidate(
                        home = Path.of("/fixture/jdk-17"),
                        feature = JavaFeature.of(17),
                        runtimeVersion = "fixture-17",
                        source = GradleJvmSelectionSource.PLATFORM_RESOLVER,
                    ),
                ),
            ),
        )
        return SelectedGradleJvm.establish(selection, selector)
    }
}
