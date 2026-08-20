package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InstalledProjectJvmTest {
    @Test
    fun `project JVM retains the admitted physical Java home without a repository SDK name`() {
        val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
        val gradleJvm = when (
            val admission = InstalledGradleJvm.admit(javaHome.toString(), javaHome.toString())
        ) {
            is InstalledGradleJvmAdmission.Admitted -> admission.jvm
            is InstalledGradleJvmAdmission.Rejected -> error(admission.failure)
        }

        val projectJvm = InstalledProjectJvm.from(gradleJvm)

        assertEquals(javaHome, projectJvm.home)
    }
}
