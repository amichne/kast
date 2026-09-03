package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InstalledSidecarJvmTest {
    @Test
    fun `current physical java home refines to Gradle JVM authority`() {
        val javaHome = System.getProperty("java.home")
        val admission = InstalledSidecarJvm.admit(javaHome, javaHome)

        assertInstanceOf(InstalledSidecarJvmAdmission.Admitted::class.java, admission)
    }

    @Test
    fun `relative java home fails closed`() {
        val admission = InstalledSidecarJvm.admit("relative-java-home", null)

        val rejected = assertInstanceOf(
            InstalledSidecarJvmAdmission.Rejected::class.java,
            admission,
        )
        assertEquals(InstalledSidecarJvmFailure.NOT_ABSOLUTE, rejected.failure)
    }
}
