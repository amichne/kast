package io.github.amichne.kast.standalone.profiling

import io.github.amichne.kast.api.client.fields.ProfilingMode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProfilingManagerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `start writes manifest for successful profiler launches`() {
        val outputDir = tempDir.resolve("profiling")
        val manager = ProfilingManager(
            config = ProfilingConfig(
                enabled = true,
                modes = setOf(ProfilingMode.CPU, ProfilingMode.ALLOCATION),
                durationSeconds = 30L,
                outputDir = outputDir,
                otlpEndpoint = null,
                emitManifest = true,
            ),
            pid = 4242L,
            timestampProvider = { "20260510T010000Z" },
            launcher = { run ->
                Files.createDirectories(run.outputFile.parent)
                Files.writeString(run.outputFile, "artifact for ${run.mode}")
                FakeRunningProfiler(exitCode = 0)
            },
        )

        assertTrue(manager.start())
        assertTrue(manager.awaitCompletion(1_000L))
        assertTrue(Files.isRegularFile(outputDir.resolve("profiling-manifest.json")))

        val manifest = Files.readString(outputDir.resolve("profiling-manifest.json"))
        assertTrue(manifest.contains("\"pid\": 4242"))
        assertTrue(manifest.contains("\"cpu\""))
        assertTrue(manifest.contains("\"allocation\""))
        assertTrue(manifest.contains(outputDir.resolve("profiling-cpu-20260510T010000Z.html").toString()))
        assertTrue(manifest.contains(outputDir.resolve("profiling-allocation-20260510T010000Z.html").toString()))
    }

    private class FakeRunningProfiler(
        private val exitCode: Int,
    ) : RunningProfiler {
        override fun waitFor(): Int = exitCode

        override fun isAlive(): Boolean = false

        override fun destroy() = Unit
    }
}
