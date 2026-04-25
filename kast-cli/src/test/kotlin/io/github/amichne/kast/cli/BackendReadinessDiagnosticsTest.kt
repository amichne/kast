package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
