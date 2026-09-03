package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkException
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class InstalledGradleImportPlanTest {
    @Test
    fun `project open defers configurators until admitted Gradle settings are available`() {
        val task = installedProjectOpenTask(InstalledProjectOpenPreparation(admittedGradleJvm()))

        assertFalse(task.runConfigurators)
    }

    @Test
    fun `linked exact workspace refreshes explicitly after project open`() {
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
    }

    @Test
    fun `unlinked exact workspace is linked`() {
        val application = assertInstanceOf(
            InstalledGradleImportApplication.Applied::class.java,
            InstalledGradleLinkPresence.Unlinked.applyImportJvm(admittedGradleJvm()),
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

    private fun admittedGradleJvm(): InstalledGradleJvm {
        val javaHome = System.getProperty("java.home")
        return when (val admission = InstalledGradleJvm.admit(javaHome, javaHome)) {
            is InstalledGradleJvmAdmission.Admitted -> admission.jvm
            is InstalledGradleJvmAdmission.Rejected -> error(admission.failure)
        }
    }
}
