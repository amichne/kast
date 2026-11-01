package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleHomePropagationTest {
    @Test
    fun `service environment carries selected home without ambient JVM options`() {
        val home = Path.of("/selected/home with spaces")
        val installation = Path.of("/selected/installation")
        val inherited =
            mapOf(
                "HOME" to "/foreign/home",
                "PATH" to "/usr/bin:/bin",
                "JAVA_HOME" to "/selected/java",
                "CODEX_EXECUTABLE" to "/selected/codex",
                "TMPDIR" to "/selected/tmp",
                "JAVA_OPTS" to "-Dkast.foreign=java-opts",
                "JAVA_TOOL_OPTIONS" to "-Dkast.foreign=tool-options",
                "JDK_JAVA_OPTIONS" to "-Dkast.foreign=jdk-options",
                "_JAVA_OPTIONS" to "-Dkast.foreign=legacy-options",
                "KAST_OPTS" to "-Dkast.foreign=kast-options",
                "KAST_CONFIGURATION_FILE" to "/foreign/config",
            )
        assertEquals(
            mapOf(
                "HOME" to home.toString(),
                "PATH" to "/usr/bin:/bin",
                "JAVA_HOME" to "/selected/java",
                "CODEX_EXECUTABLE" to "/selected/codex",
                "TMPDIR" to "/selected/tmp",
                "KAST_CONFIGURATION_FILE" to "/selected/installation/config/environment",
                "JAVA_TOOL_OPTIONS" to "'-Duser.home=/selected/home with spaces'",
            ),
            installationLifecycleEnvironment(home, installation, inherited),
        )
    }

    @Test
    fun `home propagation does not select a Java runtime`() {
        val environment = installationLifecycleEnvironment(Path.of("/home"), Path.of("/installation"), emptyMap())
        assertFalse("JAVA_HOME" in environment)
        assertFalse("PATH" in environment)
    }

    @Test
    fun `JVM receives exact home as one property rather than executable options`() {
        for (home in
            listOf(
                "/selected/home with spaces",
                "/selected/a'b\"c\\d\$() -Dkast.foreign=injected",
                "/selected/line\nbreak\ttab",
            )) {
            val builder =
                ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    LifecycleHomeProbe::class.java.name,
                )
            builder.environment().clear()
            builder
                .environment()
                .putAll(
                    installationLifecycleEnvironment(
                        Path.of(home),
                        Path.of("/installation"),
                        mapOf("JAVA_TOOL_OPTIONS" to "-Dkast.foreign=ambient"),
                    )
                )
            val child = builder.start()
            try {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                val stderr = child.errorReader().readText()
                assertEquals(0, child.exitValue(), stderr)
                assertEquals(home, child.inputReader().readText())
            } finally {
                if (child.isAlive) child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            }
        }
    }
}

internal object LifecycleHomeProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        check(System.getProperty("kast.foreign") == null)
        print(System.getProperty("user.home"))
    }
}
