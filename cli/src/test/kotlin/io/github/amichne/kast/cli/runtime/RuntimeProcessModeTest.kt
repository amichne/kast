package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RuntimeProcessModeTest {
    @Test
    fun `missing environment flag selects direct process launch`() {
        assertEquals(
            RuntimeProcessModeAdmission.Admitted(RuntimeProcessMode.Direct),
            RuntimeProcessModeEnvironment.admit(null),
        )
    }

    @Test
    fun `zero environment flag explicitly selects direct process launch`() {
        assertEquals(
            RuntimeProcessModeAdmission.Admitted(RuntimeProcessMode.Direct),
            RuntimeProcessModeEnvironment.admit("0"),
        )
    }

    @Test
    fun `one environment flag explicitly selects launchd process launch`() {
        assertEquals(
            RuntimeProcessModeAdmission.Admitted(RuntimeProcessMode.Launchd),
            RuntimeProcessModeEnvironment.admit("1"),
        )
    }

    @Test
    fun `unsupported environment values remain closed failures`() {
        listOf("", " ", "true", "yes", "2").forEach { configured ->
            assertEquals(
                RuntimeProcessModeAdmission.Rejected(
                    RuntimeProcessModeFailure.INVALID_ENVIRONMENT_VALUE,
                ),
                RuntimeProcessModeEnvironment.admit(configured),
                "configured value: '$configured'",
            )
        }
    }

    @Test
    fun `invalid launchd flag retains an actionable bootstrap reason`() {
        val failure = InstalledCompositionFailure.RuntimeProcessModeRejected(
            RuntimeProcessModeFailure.INVALID_ENVIRONMENT_VALUE,
        )

        assertEquals("invalid-launchd-flag", failure.outputReason)
    }

    @Test
    fun `each admitted mode selects one matched starter and lifecycle authority`() {
        val direct = RuntimeProcessMode.Direct.capabilities()
        assertSame(JdkRuntimeProcessStarter, direct.starter)
        assertSame(JdkRuntimeProcessAuthority, direct.authority)

        val launchd = RuntimeProcessMode.Launchd.capabilities()
        assertSame(LaunchdRuntimeProcessStarter, launchd.starter)
        assertSame(LaunchdRuntimeProcessAuthority, launchd.authority)
    }

    @Test
    fun `detached process environment exposes only the allowlisted variables`() {
        val resolved = assertInstanceOf(
            MacOsRuntimeProcessEnvironmentResolution.Resolved::class.java,
            MacOsRuntimeProcessEnvironment.resolve(),
        )

        assertEquals(setOf("JAVA_HOME", "HOME", "PATH"), resolved.environment.variables.keys)
    }
}
