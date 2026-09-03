package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkException
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class InstalledGradleImportPlanTest {
    @Test
    fun `project open defers configurators until admitted Gradle settings are available`() {
        val task = installedProjectOpenTask(
            InstalledProjectOpenPreparation(BootstrapProjectJvm.from(admittedSidecarJvm())),
        )

        assertFalse(task.runConfigurators)
    }

    @Test
    fun `linked exact workspace refreshes explicitly after project open`() {
        val linkedSettings = GradleProjectSettings("/workspace").apply {
            gradleJvm = "prior-jvm"
        }
        val gradleJvm = selectedGradleJvmFixture()

        val application = assertInstanceOf(
            InstalledGradleImportApplication.Applied::class.java,
            InstalledGradleLinkPresence.Linked(linkedSettings).applyImportJvm(gradleJvm),
        )

        assertInstanceOf(
            InstalledGradleImportOperation.RefreshLinked::class.java,
            application.operation,
        )
        assertEquals(gradleJvm.projectSettingsSelector(), linkedSettings.gradleJvm)
    }

    @Test
    fun `unlinked exact workspace is linked`() {
        val application = assertInstanceOf(
            InstalledGradleImportApplication.Applied::class.java,
            InstalledGradleLinkPresence.Unlinked(
                GradleProjectSettings("/workspace"),
            ).applyImportJvm(selectedGradleJvmFixture()),
        )
        assertInstanceOf(InstalledGradleImportOperation.LinkUnlinked::class.java, application.operation)
    }

    @Test
    fun `invalid Gradle JVM callback retains its exact terminal cause`() {
        val callback = CompletableFuture<Void>()
        callback.completeExceptionally(
            ExternalSystemJdkException("invalid fixture JDK", null),
        )

        assertEquals(
            InstalledGradleImportOutcome.InvalidJvmConfiguration,
            callback.closedImportOutcome().get(1, TimeUnit.SECONDS),
        )
    }

    private fun admittedSidecarJvm(): InstalledSidecarJvm {
        val javaHome = System.getProperty("java.home")
        return when (val admission = InstalledSidecarJvm.admit(javaHome, javaHome)) {
            is InstalledSidecarJvmAdmission.Admitted -> admission.jvm
            is InstalledSidecarJvmAdmission.Rejected -> error(admission.failure)
        }
    }

    private fun selectedGradleJvmFixture(): SelectedGradleJvm {
        val selection = assertInstanceOf(
            GradleJvmCandidateSelection.Selected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("7.6"),
                listOf(
                    GradleJvmCandidate(
                        home = java.nio.file.Path.of("/fixture/jdk-17"),
                        feature = JavaFeature.of(17),
                        runtimeVersion = "fixture-17",
                        source = GradleJvmSelectionSource.PLATFORM_RESOLVER,
                    ),
                ),
            ),
        )
        return SelectedGradleJvm.establish(selection, "fixture-jdk-17")
    }
}
