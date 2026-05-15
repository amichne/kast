package io.github.amichne.kast.standalone.profiling

import io.github.amichne.kast.api.client.fields.ProfilingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

@Serializable
internal data class ProfilingManifest(
    val version: String = "1",
    val timestamp: String,
    val pid: Long,
    val modes: List<String>,
    val durationSeconds: Long,
    val artifacts: Map<String, ArtifactMetadata>,
) {
    @Serializable
    data class ArtifactMetadata(
        val type: String,
        val path: String,
        val sizeBytes: Long?,
    )
}

internal data class ProfilingRun(
    val mode: ProfilingMode,
    val outputFile: Path,
    val durationSeconds: Long,
    val pid: Long,
)

internal interface RunningProfiler {
    fun waitFor(): Int

    fun isAlive(): Boolean

    fun destroy()
}

internal class ProfilingManager(
    private val config: ProfilingConfig,
    private val pid: Long = ProcessHandle.current().pid(),
    private val timestampProvider: () -> String = {
        TIMESTAMP_FORMATTER.format(Instant.now())
    },
    private val launcher: (ProfilingRun) -> RunningProfiler = { run ->
        ProcessRunningProfiler(
            ProcessBuilder(
                "asprof",
                "-d",
                run.durationSeconds.toString(),
                "-e",
                run.mode.asyncProfilerEvent,
                "-f",
                run.outputFile.toString(),
                run.pid.toString(),
            ).start(),
        )
    },
) : AutoCloseable {
    private val json = Json { prettyPrint = true }
    private val launchedProfilers = mutableListOf<Pair<ProfilingRun, RunningProfiler>>()

    @Volatile
    private var watcherThread: Thread? = null

    @Volatile
    private var started = false

    fun start(): Boolean {
        if (!config.enabled || config.modes.isEmpty() || started) {
            return false
        }
        started = true
        Files.createDirectories(config.outputDir)
        val timestamp = timestampProvider()
        val launches = config.modes.mapNotNull { mode ->
            val run = ProfilingRun(
                mode = mode,
                outputFile = config.outputDir.resolve("profiling-${mode.fileLabel}-$timestamp.html"),
                durationSeconds = config.durationSeconds,
                pid = pid,
            )
            runCatching { launcher(run) }.getOrNull()?.let { run to it }
        }
        if (launches.isEmpty()) {
            return false
        }
        launchedProfilers += launches
        watcherThread = thread(
            start = true,
            isDaemon = true,
            name = "kast-profiling-manifest-writer",
        ) {
            val artifacts = launches.mapNotNull { (run, profiler) ->
                val exitCode = awaitProfiler(profiler) ?: return@thread
                if (exitCode == 0 && Files.isRegularFile(run.outputFile)) {
                    run.mode.fileLabel to ProfilingManifest.ArtifactMetadata(
                        type = "flamegraph-html",
                        path = run.outputFile.toString(),
                        sizeBytes = Files.size(run.outputFile),
                    )
                } else {
                    null
                }
            }.toMap()

            if (config.emitManifest) {
                val manifest = ProfilingManifest(
                    timestamp = timestamp,
                    pid = pid,
                    modes = config.modes.map { it.fileLabel }.sorted(),
                    durationSeconds = config.durationSeconds,
                    artifacts = artifacts,
                )
                Files.writeString(
                    config.outputDir.resolve("profiling-manifest.json"),
                    json.encodeToString(manifest),
                )
            }
        }
        return true
    }

    fun awaitCompletion(timeoutMillis: Long): Boolean {
        val thread = watcherThread ?: return true
        thread.join(timeoutMillis)
        return !thread.isAlive
    }

    override fun close() {
        launchedProfilers.forEach { (_, profiler) ->
            if (profiler.isAlive()) {
                profiler.destroy()
            }
        }
        watcherThread?.interrupt()
        watcherThread?.join(2_000L)
    }

    private fun awaitProfiler(profiler: RunningProfiler): Int? {
        while (profiler.isAlive()) {
            if (Thread.currentThread().isInterrupted) {
                return null
            }
            Thread.sleep(25L)
        }
        return profiler.waitFor()
    }

    private class ProcessRunningProfiler(
        private val process: Process,
    ) : RunningProfiler {
        override fun waitFor(): Int = process.waitFor()

        override fun isAlive(): Boolean = process.isAlive

        override fun destroy() {
            process.destroy()
        }
    }

    private companion object {
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}

private val ProfilingMode.fileLabel: String
    get() = when (this) {
        ProfilingMode.CPU -> "cpu"
        ProfilingMode.ALLOCATION -> "allocation"
        ProfilingMode.LOCK -> "lock"
        ProfilingMode.WALL -> "wall"
    }

private val ProfilingMode.asyncProfilerEvent: String
    get() = when (this) {
        ProfilingMode.CPU -> "cpu"
        ProfilingMode.ALLOCATION -> "alloc"
        ProfilingMode.LOCK -> "lock"
        ProfilingMode.WALL -> "wall"
    }
