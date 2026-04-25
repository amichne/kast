package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackendReadinessDiagnosticsTest {
    @Test
    fun `startup timeout diagnostics include actionable backend readiness evidence`() {
        val message = BackendReadinessFailureDiagnostics(
            workspace = Path.of("/workspace"),
            timeoutMillis = 1_000,
            commandSummary = "java -cp <12 runtime libs> io.github.amichne.kast.standalone.StandaloneMainKt --workspace-root=/workspace",
            runtimeLibsSummary = "runtimeLibs=/runtime-libs; classpath.txt exists=true; entries=12",
            processExitCode = 17,
            backendStdout = "backend stdout line",
            backendStderr = "backend stderr line",
            lastStatusProbe = BackendStatusProbeSnapshot(
                exitCode = 2,
                stdout = """{"selected":{"ready":false},"candidates":[]}""",
                stderr = "status stderr line",
            ),
        ).toErrorMessage()

        assertTrue(message.contains("Timed out waiting for standalone backend at /workspace"))
        assertTrue(message.contains("timeoutMillis=1000"))
        assertTrue(message.contains("startupCommand=java -cp <12 runtime libs>"))
        assertTrue(message.contains("runtimeLibs=/runtime-libs"))
        assertTrue(message.contains("backendExitCode=17"))
        assertTrue(message.contains("backendStdout=backend stdout line"))
        assertTrue(message.contains("backendStderr=backend stderr line"))
        assertTrue(message.contains("lastStatusProbe.exitCode=2"))
        assertTrue(message.contains("lastStatusProbe.stdout={\"selected\":{\"ready\":false},\"candidates\":[]}"))
        assertTrue(message.contains("lastStatusProbe.stderr=status stderr line"))
    }

    @Test
    fun `missing runtime libs property uses backend readiness diagnostics`(@TempDir workspace: Path) {
        val error = withSystemProperty("kast.runtime-libs", null) {
            assertThrows(IllegalStateException::class.java) {
                startStandaloneBackendForTest(
                    workspace = workspace,
                    timeoutMillis = 1,
                    statusProbe = { BackendStatusProbeSnapshot() },
                    isReady = { false },
                )
            }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("Failed before standalone backend readiness polling at $workspace"))
        assertTrue(message.contains("failure=kast.runtime-libs system property is missing"))
        assertTrue(message.contains("startupCommand=<not-built>"))
        assertTrue(message.contains("runtimeLibs=<missing>"))
        assertTrue(message.contains("backendExitCode=<not-started>"))
        assertTrue(message.contains("lastStatusProbe=<none>"))
    }

    @Test
    fun `missing classpath file uses backend readiness diagnostics`(@TempDir workspace: Path, @TempDir runtimeLibs: Path) {
        val error = withSystemProperty("kast.runtime-libs", runtimeLibs.toString()) {
            assertThrows(IllegalStateException::class.java) {
                startStandaloneBackendForTest(
                    workspace = workspace,
                    timeoutMillis = 1,
                    statusProbe = { BackendStatusProbeSnapshot() },
                    isReady = { false },
                )
            }
        }

        val classpathFile = runtimeLibs.resolve("classpath.txt")
        val message = error.message.orEmpty()
        assertTrue(message.contains("Failed before standalone backend readiness polling at $workspace"))
        assertTrue(message.contains("failure=runtime classpath file is missing or unreadable: $classpathFile"))
        assertTrue(message.contains("startupCommand=<not-built>"))
        assertTrue(message.contains("runtimeLibs=$runtimeLibs"))
        assertTrue(message.contains("classpathFile=$classpathFile"))
        assertTrue(message.contains("classpath.txt exists=false"))
        assertTrue(message.contains("entries=0"))
        assertTrue(message.contains("backendExitCode=<not-started>"))
        assertTrue(message.contains("lastStatusProbe=<none>"))
    }

    @Test
    fun `empty classpath file uses backend readiness diagnostics`(@TempDir workspace: Path, @TempDir runtimeLibs: Path) {
        val classpathFile = Files.writeString(runtimeLibs.resolve("classpath.txt"), "\n")
        val error = withSystemProperty("kast.runtime-libs", runtimeLibs.toString()) {
            assertThrows(IllegalStateException::class.java) {
                startStandaloneBackendForTest(
                    workspace = workspace,
                    timeoutMillis = 1,
                    statusProbe = { BackendStatusProbeSnapshot() },
                    isReady = { false },
                )
            }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("Failed before standalone backend readiness polling at $workspace"))
        assertTrue(message.contains("failure=runtime classpath file has no entries: $classpathFile"))
        assertTrue(message.contains("classpath.txt exists=true"))
        assertTrue(message.contains("entries=0"))
        assertTrue(message.contains("backendExitCode=<not-started>"))
        assertTrue(message.contains("lastStatusProbe=<none>"))
    }

    @Test
    fun `process start failure uses backend readiness diagnostics`(@TempDir workspace: Path, @TempDir runtimeLibs: Path) {
        Files.writeString(runtimeLibs.resolve("classpath.txt"), "backend.jar\n")
        val missingJava = workspace.resolve("missing-java").toString()
        val error = withSystemProperty("kast.runtime-libs", runtimeLibs.toString()) {
            assertThrows(IllegalStateException::class.java) {
                startStandaloneBackendForTest(
                    workspace = workspace,
                    timeoutMillis = 1,
                    javaExecutable = missingJava,
                    statusProbe = { BackendStatusProbeSnapshot() },
                    isReady = { false },
                )
            }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("Failed before standalone backend readiness polling at $workspace"))
        assertTrue(message.contains("failure=java.io.IOException: Cannot run program"))
        assertTrue(message.contains(missingJava))
        assertTrue(message.contains("startupCommand=$missingJava -cp <1 runtime libs from $runtimeLibs>"))
        assertTrue(message.contains("runtimeLibs=$runtimeLibs"))
        assertTrue(message.contains("classpath.txt exists=true"))
        assertTrue(message.contains("entries=1"))
        assertTrue(message.contains("backendExitCode=<not-started>"))
        assertTrue(message.contains("backendStdout=<empty>"))
        assertTrue(message.contains("backendStderr=<empty>"))
        assertTrue(message.contains("lastStatusProbe=<none>"))
    }

    private fun <T> withSystemProperty(name: String, value: String?, block: () -> T): T {
        val previous = System.getProperty(name)
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, previous)
            }
        }
    }
}
