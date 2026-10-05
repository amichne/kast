package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerServiceJavaRuntimeTest {
    @Test
    fun `missing selected IDE rejects rather than using ambient Java or the current JVM`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
            BrokerServiceLaunchCommand.resolveCoordinator(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("JAVA_HOME" to "\u0000"),
            ),
        )
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
            BrokerServiceLaunchCommand.resolveCoordinator(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("JAVA_HOME" to System.getProperty("java.home")),
            ),
        )
    }

    @Test
    fun `installed IDE selection fixes service Java identity across caller runtimes`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val idea = Files.createDirectories(temporary.resolve("IDEA With Spaces.app/Contents")).toRealPath()
        val javaHome = Files.createDirectories(idea.resolve("jbr/Contents/Home"))
        executable(Files.createDirectories(javaHome.resolve("bin")).resolve("java"))
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n")
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

    @Test
    fun `incompatible selected JBR does not fall back to valid ambient Java`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val selected = selectedBrokerJbr(temporary)
        val javaHome = Path.of(selected.second).resolve("jbr/Contents/Home")
        val environment = fixture.environment + selected + ("JAVA_HOME" to System.getProperty("java.home"))
        for (release in
            listOf(
                "JAVA_VERSION=\"24.0.2\"\n",
                "JAVA_VERSION=\"bad\"\n",
                "JAVA_VERSION=\"25\"\nJAVA_VERSION=\"24\"\n",
            )) {
            Files.writeString(javaHome.resolve("release"), release)
            assertEquals(
                BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
                BrokerServiceLaunchCommand.resolveCoordinator(fixture.kast, fixture.userHome, environment),
            )
        }
        Files.delete(javaHome.resolve("release"))
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
            BrokerServiceLaunchCommand.resolveCoordinator(fixture.kast, fixture.userHome, environment),
        )
    }

    @Test
    fun `owned launch receipt runtime override retains precedence and compatibility proof`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val selected = selectedBrokerJbr(temporary)
        val runtime = Files.createDirectories(temporary.resolve("recorded-runtime"))
        executable(Files.createDirectories(runtime.resolve("bin")).resolve("java"))
        Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"25.0.2\"\n")
        val environment = fixture.environment + selected
        val result =
            BrokerServiceLaunchCommand.resolve(
                kastCandidate = fixture.kast,
                userHomeCandidate = fixture.userHome,
                environment = environment,
                javaHomeCandidate = runtime,
                purpose = BrokerServicePurpose.COORDINATOR,
            )
        assertEquals(runtime.toRealPath(), (result as BrokerServiceLaunchCommandResolution.Resolved).command.javaHome)
        Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"24\"\n")
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE),
            BrokerServiceLaunchCommand.resolve(
                kastCandidate = fixture.kast,
                userHomeCandidate = fixture.userHome,
                environment = environment,
                javaHomeCandidate = runtime,
                purpose = BrokerServicePurpose.COORDINATOR,
            ),
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
