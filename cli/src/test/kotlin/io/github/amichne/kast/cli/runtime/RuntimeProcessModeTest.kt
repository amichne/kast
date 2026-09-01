package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
    fun `detached process environment exposes only the allowlisted variables`(
        @TempDir temporary: Path,
    ) {
        val resolved = assertInstanceOf(
            MacOsRuntimeProcessEnvironmentResolution.Resolved::class.java,
            MacOsRuntimeProcessEnvironment.resolve(installedRuntime(temporary)),
        )

        assertEquals(setOf("JAVA_HOME", "HOME", "PATH"), resolved.environment.variables.keys)
    }

    @Test
    fun `detached process environment uses the admitted IDEA JBR`(
        @TempDir temporary: Path,
    ) {
        val runtime = installedRuntime(temporary)
        val jbrHome = runtime.javaExecutable.parent.parent

        val resolved = assertInstanceOf(
            MacOsRuntimeProcessEnvironmentResolution.Resolved::class.java,
            MacOsRuntimeProcessEnvironment.resolve(runtime),
        )

        assertEquals(jbrHome.toString(), resolved.environment.variables["JAVA_HOME"])
        assertEquals(
            "${jbrHome.resolve("bin")}:/usr/bin:/bin:/usr/sbin:/sbin",
            resolved.environment.variables["PATH"],
        )
        assertNotEquals(
            Path.of(System.getProperty("java.home")).toRealPath().toString(),
            resolved.environment.variables["JAVA_HOME"],
        )

        val alternateJava = Files.createDirectories(temporary.resolve("alternate/bin"))
            .resolve("java")
        Files.writeString(alternateJava, "#!/bin/sh\nexit 0\n")
        alternateJava.toFile().setExecutable(true)
        assertEquals(
            MacOsRuntimeProcessEnvironmentResolution.Rejected(
                MacOsRuntimeProcessEnvironmentFailure.JAVA_HOME_UNAVAILABLE,
            ),
            MacOsRuntimeProcessEnvironment.resolve(
                InstalledIdeRuntime(runtime.home, alternateJava.toRealPath(), runtime.identity),
            ),
        )
    }

    @Test
    fun `detached process environment accepts a physically owned symlinked IDEA JBR`(
        @TempDir temporary: Path,
    ) {
        val runtime = installedRuntime(temporary.resolve("managed-runtime"))
        val ideaHome = Files.createDirectories(
            temporary.resolve("IntelliJ IDEA.app/Contents"),
        ).toRealPath()
        Files.createSymbolicLink(
            ideaHome.resolve("jbr"),
            runtime.javaExecutable.parent.parent.parent.parent,
        )
        val linkedRuntime = InstalledIdeRuntime(ideaHome, runtime.javaExecutable, runtime.identity)

        val resolved = assertInstanceOf(
            MacOsRuntimeProcessEnvironmentResolution.Resolved::class.java,
            MacOsRuntimeProcessEnvironment.resolve(linkedRuntime),
        )

        assertEquals(
            runtime.javaExecutable.parent.parent.toString(),
            resolved.environment.variables["JAVA_HOME"],
        )
    }

    private fun installedRuntime(temporary: Path): InstalledIdeRuntime {
        val ideaHome = Files.createDirectories(
            temporary.resolve("IntelliJ IDEA.app/Contents"),
        ).toRealPath()
        val jbrHome = Files.createDirectories(
            ideaHome.resolve("jbr/Contents/Home"),
        ).toRealPath()
        val javaExecutable = Files.createDirectories(jbrHome.resolve("bin"))
            .resolve("java")
        Files.writeString(javaExecutable, "#!/bin/sh\nexit 0\n")
        javaExecutable.toFile().setExecutable(true)
        val pair = SupportedIdeRuntimePair.admit(
            "262.9437.185",
            "262.9437.185-IJ",
        ).let { (it as SupportedIdeRuntimePairAdmission.Admitted).pair }
        val identity = IdeRuntimeIdentity.admit(
            pair,
            IdeRuntimeIdentityCandidate(
                pair.ideaBuild,
                pair.kotlinPluginBuild,
                "jbr-25.0.3+9-b508.16-aarch64",
                "sha256:${"a".repeat(64)}",
            ),
        ).let { (it as IdeRuntimeIdentityAdmission.Admitted).identity }
        return InstalledIdeRuntime(ideaHome, javaExecutable.toRealPath(), identity)
    }
}
