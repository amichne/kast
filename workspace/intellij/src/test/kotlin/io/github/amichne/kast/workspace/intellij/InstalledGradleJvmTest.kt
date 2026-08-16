package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InstalledGradleJvmTest {
    @Test
    fun `current physical java home refines to Gradle JVM authority`() {
        val javaHome = System.getProperty("java.home")
        val admission = InstalledGradleJvm.admit(javaHome, javaHome)

        assertInstanceOf(InstalledGradleJvmAdmission.Admitted::class.java, admission)
    }

    @Test
    fun `relative java home fails closed`() {
        val admission = InstalledGradleJvm.admit("relative-java-home", null)

        val rejected = assertInstanceOf(
            InstalledGradleJvmAdmission.Rejected::class.java,
            admission,
        )
        assertEquals(InstalledGradleJvmFailure.NOT_ABSOLUTE, rejected.failure)
    }
}
