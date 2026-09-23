package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerServiceJavaRuntimeTest {
    @Test
    fun `invalid ambient Java home remains a finite failure without an installed IDE selection`(
        @TempDir temporary: Path
    ) {
        val fixture = fixture(temporary)
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
            BrokerServiceLaunchCommand.resolveCoordinator(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("JAVA_HOME" to "\u0000"),
            ),
        )
    }

    @Test
    fun `installed IDE selection fixes service Java identity across caller runtimes`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val idea = Files.createDirectories(temporary.resolve("idea")).toRealPath()
        val javaHome = Files.createDirectories(idea.resolve("jbr/Contents/Home"))
        executable(Files.createDirectories(javaHome.resolve("bin")).resolve("java"))
        val otherJava = Files.createDirectories(temporary.resolve("other-java"))
        executable(Files.createDirectories(otherJava.resolve("bin")).resolve("java"))
        val environment =
            fixture.environment +
                mapOf("KAST_INSTALL_IDEA_HOME" to idea.toString(), "JAVA_HOME" to otherJava.toString())

        val selected =
            BrokerServiceLaunchCommand.resolveCoordinator(fixture.kast, fixture.userHome, environment)
                as BrokerServiceLaunchCommandResolution.Resolved
        assertEquals(javaHome.toRealPath(), selected.command.javaHome)
        assertEquals(javaHome.resolve("bin/java").toRealPath(), selected.command.javaExecutable)

        Files.delete(javaHome.resolve("bin/java"))
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
            BrokerServiceLaunchCommand.resolveCoordinator(fixture.kast, fixture.userHome, environment),
        )
    }

    private fun fixture(root: Path): Fixture {
        val product = Files.createDirectories(root.resolve("product")).toRealPath()
        val kast = executable(Files.createDirectories(product.resolve("bin")).resolve("kast"))
        val userHome = Files.createDirectories(root.resolve("home")).toRealPath()
        return Fixture(kast, userHome, mapOf("PATH" to "/usr/bin:/bin"))
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }

    private data class Fixture(val kast: Path, val userHome: Path, val environment: Map<String, String>)
}
