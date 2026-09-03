package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BootstrapProjectJvmTest {
    @Test
    fun `project JVM retains the admitted physical Java home without a repository SDK name`() {
        val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
        val sidecarJvm = when (
            val admission = InstalledSidecarJvm.admit(javaHome.toString(), javaHome.toString())
        ) {
            is InstalledSidecarJvmAdmission.Admitted -> admission.jvm
            is InstalledSidecarJvmAdmission.Rejected -> error(admission.failure)
        }

        val projectJvm = BootstrapProjectJvm.from(sidecarJvm)

        assertEquals(javaHome, projectJvm.home)
    }
}
