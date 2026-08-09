package io.github.amichne.kast.indexer

import io.github.amichne.kast.api.client.fields.ProfilingMode
import io.github.amichne.kast.api.client.fields.ProfilingModeResolution
import io.github.amichne.kast.api.client.fields.ProfilingModeSelection
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

internal object KastRuntimeProfiling {
    /**
     * Proof transition: `RuntimeProfilingLaunch -> RuntimeProfilingStart`.
     *
     * Establishes disabled ownership, or an active recording capability bound
     * to one runtime instance, canonical workspace, exact Git source head,
     * start time, duration, supported mode set, and owned artifact paths.
     * Expected rejection is the closed [RuntimeProfilingFailure] family. Raw
     * paths and serialized manifest primitives are extracted only at the Git,
     * filesystem, JFR, and JSON adapter boundaries in this file.
     */
    fun start(launch: RuntimeProfilingLaunch): RuntimeProfilingStart {
        if (!launch.config.enabled.value) return RuntimeProfilingStart.Disabled
        val selection = when (val resolution = launch.config.modes.resolve()) {
            is ProfilingModeResolution.Resolved -> resolution.selection
            is ProfilingModeResolution.Rejected -> {
                return RuntimeProfilingStart.Rejected(RuntimeProfilingFailure.InvalidModes(resolution.failure))
            }
        }
        val duration = when (val resolution = RuntimeProfileDuration.resolve(launch.config.durationSeconds.value)) {
            is RuntimeProfileDurationResolution.Resolved -> resolution.duration
            is RuntimeProfileDurationResolution.Rejected -> return RuntimeProfilingStart.Rejected(resolution.failure)
        }
        val outputRoot = when (val resolution = RuntimeProfileOutputRoot.resolve(launch)) {
            is RuntimeProfileOutputResolution.Resolved -> resolution.root
            is RuntimeProfileOutputResolution.Rejected -> return RuntimeProfilingStart.Rejected(resolution.failure)
        }
        val workspace = runCatching { RuntimeProfileWorkspace(launch.workspaceRoot.toRealPath()) }
            .getOrElse { failure ->
                return RuntimeProfilingStart.Rejected(
                    RuntimeProfilingFailure.WorkspaceUnavailable(launch.workspaceRoot, failure.failureText()),
                )
            }
        val sourceHead = when (val resolution = GitSourceHeadResolver.resolve(workspace.path)) {
            is RuntimeSourceHeadResolution.Resolved -> resolution.sourceHead
            is RuntimeSourceHeadResolution.Rejected -> return RuntimeProfilingStart.Rejected(resolution.failure)
        }
        return startRecordings(
            launch = launch,
            selection = selection,
            duration = duration,
            outputRoot = outputRoot,
            workspace = workspace,
            sourceHead = sourceHead,
        )
    }

    private fun startRecordings(
        launch: RuntimeProfilingLaunch,
        selection: ProfilingModeSelection,
        duration: RuntimeProfileDuration,
        outputRoot: RuntimeProfileOutputRoot,
        workspace: RuntimeProfileWorkspace,
        sourceHead: RuntimeProfileSourceHead,
    ): RuntimeProfilingStart {
        val startedAt = launch.clock.instant()
        val sessionRoot = outputRoot.path.resolve("${sourceHead.value}-${launch.runtimeInstanceId.value}")
        try {
            Files.createDirectories(outputRoot.path)
            Files.createDirectory(sessionRoot)
        } catch (failure: Exception) {
            return RuntimeProfilingStart.Rejected(
                RuntimeProfilingFailure.ArtifactPreparationFailed(sessionRoot, failure.failureText()),
            )
        }
        val recordings = mutableListOf<OwnedRuntimeRecording>()
        try {
            selection.modes.forEach { mode ->
                val artifact = sessionRoot.resolve("${mode.wireName}.jfr")
                val recording = OwnedRuntimeRecording(mode, artifact, Recording())
                recordings += recording
                recording.recording.apply {
                    name = "kast-${mode.wireName}-${launch.runtimeInstanceId.value}"
                    configure(mode)
                    destination = artifact
                    this.duration = duration.value
                    start()
                }
            }
            if (launch.config.emitManifest.value) {
                writeManifest(
                    path = sessionRoot.resolve(MANIFEST_FILE_NAME),
                    manifest = RuntimeProfilingManifest(
                        runtimeImplementationVersion = launch.runtimeVersion.value,
                        runtimeInstanceId = launch.runtimeInstanceId.value,
                        workspaceRoot = workspace.path.toString(),
                        sourceHead = sourceHead.value,
                        startedAt = startedAt.toString(),
                        durationSeconds = duration.value.seconds,
                        modes = selection.modes.map(ProfilingMode::wireName),
                        artifacts = recordings.associate { it.mode.wireName to it.path.toString() },
                    ),
                )
            }
            return RuntimeProfilingStart.Started(RuntimeProfilingSession(recordings))
        } catch (failure: Exception) {
            recordings.closeAndDelete()
            runCatching { Files.deleteIfExists(sessionRoot.resolve(MANIFEST_FILE_NAME)) }
            sessionRoot.deleteIfEmpty()
            return RuntimeProfilingStart.Rejected(
                RuntimeProfilingFailure.RecorderStartFailed(sessionRoot, failure.failureText()),
            )
        }
    }

    private fun Recording.configure(mode: ProfilingMode) {
        when (mode) {
            ProfilingMode.CPU -> {
                enable("jdk.ExecutionSample").withPeriod(SAMPLE_PERIOD)
                enable("jdk.NativeMethodSample").withPeriod(SAMPLE_PERIOD)
            }

            ProfilingMode.ALLOCATION -> {
                enable("jdk.ObjectAllocationSample").withPeriod(SAMPLE_PERIOD)
                enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO)
                enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO)
            }

            ProfilingMode.LOCK -> {
                enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO)
                enable("jdk.JavaMonitorWait").withThreshold(Duration.ZERO)
                enable("jdk.ThreadPark").withThreshold(Duration.ZERO)
            }

            ProfilingMode.WALL -> {
                enable("jdk.ExecutionSample").withPeriod(SAMPLE_PERIOD)
                enable("jdk.ThreadSleep").withThreshold(Duration.ZERO)
                enable("jdk.ThreadPark").withThreshold(Duration.ZERO)
                enable("jdk.JavaMonitorWait").withThreshold(Duration.ZERO)
            }
        }
    }

    private fun writeManifest(path: Path, manifest: RuntimeProfilingManifest) {
        val temporary = Files.createTempFile(path.parent, ".manifest-", ".json")
        try {
            Files.writeString(temporary, manifest.toJson().toString())
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private val SAMPLE_PERIOD: Duration = Duration.ofMillis(20L)
    private const val MANIFEST_FILE_NAME = "manifest.json"
}

@JvmInline
private value class RuntimeProfileDuration private constructor(val value: Duration) {
    companion object {
        /**
         * Proof transition: `Long -> RuntimeProfileDurationResolution`.
         *
         * Establishes a strictly positive recording duration. Non-positive
         * values remain the closed `InvalidDuration` launch failure. Raw
         * seconds are extracted only into JFR and manifest adapters.
         */
        fun resolve(seconds: Long): RuntimeProfileDurationResolution = if (seconds > 0L) {
            RuntimeProfileDurationResolution.Resolved(RuntimeProfileDuration(Duration.ofSeconds(seconds)))
        } else {
            RuntimeProfileDurationResolution.Rejected(RuntimeProfilingFailure.InvalidDuration(seconds))
        }
    }
}

private sealed interface RuntimeProfileDurationResolution {
    data class Resolved(val duration: RuntimeProfileDuration) : RuntimeProfileDurationResolution

    data class Rejected(val failure: RuntimeProfilingFailure.InvalidDuration) : RuntimeProfileDurationResolution
}

@JvmInline
private value class RuntimeProfileOutputRoot private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `RuntimeProfilingLaunch -> RuntimeProfileOutputResolution`.
         *
         * Establishes an absolute normalized artifact root after resolving the
         * logs-directory token, or returns `InvalidOutputDirectory`. Raw path
         * extraction occurs only at the filesystem boundary.
         */
        fun resolve(launch: RuntimeProfilingLaunch): RuntimeProfileOutputResolution {
            val expanded = launch.config.outputDir.value.replace(LOGS_DIRECTORY_TOKEN, launch.logsDirectory.toString())
            val boundaryPath = runCatching { Path.of(expanded) }
                .getOrElse {
                    return RuntimeProfileOutputResolution.Rejected(
                        RuntimeProfilingFailure.InvalidOutputDirectory(launch.config.outputDir.value),
                    )
                }
            return if (boundaryPath.isAbsolute) {
                RuntimeProfileOutputResolution.Resolved(RuntimeProfileOutputRoot(boundaryPath.normalize()))
            } else {
                RuntimeProfileOutputResolution.Rejected(
                    RuntimeProfilingFailure.InvalidOutputDirectory(launch.config.outputDir.value),
                )
            }
        }

        private const val LOGS_DIRECTORY_TOKEN = "{logsDir}"
    }
}

private sealed interface RuntimeProfileOutputResolution {
    data class Resolved(val root: RuntimeProfileOutputRoot) : RuntimeProfileOutputResolution

    data class Rejected(val failure: RuntimeProfilingFailure.InvalidOutputDirectory) : RuntimeProfileOutputResolution
}

@JvmInline
private value class RuntimeProfileWorkspace(val path: Path)

@JvmInline
private value class RuntimeProfileSourceHead private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> RuntimeSourceHeadResolution`.
         *
         * Establishes an exact lower-case SHA-1 or SHA-256 Git object identity,
         * or returns `SourceHeadUnavailable`. Raw text is extracted only from
         * the read-only Git process boundary.
         */
        fun resolve(workspace: Path, raw: String): RuntimeSourceHeadResolution {
            val normalized = raw.trim().lowercase()
            return if (SOURCE_HEAD.matches(normalized)) {
                RuntimeSourceHeadResolution.Resolved(RuntimeProfileSourceHead(normalized))
            } else {
                RuntimeSourceHeadResolution.Rejected(
                    RuntimeProfilingFailure.SourceHeadUnavailable(workspace, "Git HEAD was not an exact object ID"),
                )
            }
        }

        private val SOURCE_HEAD = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}

private sealed interface RuntimeSourceHeadResolution {
    data class Resolved(val sourceHead: RuntimeProfileSourceHead) : RuntimeSourceHeadResolution

    data class Rejected(val failure: RuntimeProfilingFailure.SourceHeadUnavailable) : RuntimeSourceHeadResolution
}

private object GitSourceHeadResolver {
    /**
     * Proof transition: `Path -> RuntimeSourceHeadResolution`.
     *
     * Establishes an exact Git source identity for the canonical workspace or
     * returns the closed source-head failure. Raw process output is consumed
     * only by [RuntimeProfileSourceHead.resolve].
     */
    fun resolve(workspace: Path): RuntimeSourceHeadResolution {
        val builder = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .directory(workspace.toFile())
            .redirectErrorStream(true)
        builder.environment().apply {
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_OPTIONAL_LOCKS", "0")
            remove("XDG_CONFIG_HOME")
            keys.removeAll(GIT_REPOSITORY_SELECTORS)
        }
        return runCatching {
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) {
                RuntimeProfileSourceHead.resolve(workspace, output)
            } else {
                RuntimeSourceHeadResolution.Rejected(
                    RuntimeProfilingFailure.SourceHeadUnavailable(workspace, output.trim().ifBlank { "git failed" }),
                )
            }
        }.getOrElse { failure ->
            RuntimeSourceHeadResolution.Rejected(
                RuntimeProfilingFailure.SourceHeadUnavailable(workspace, failure.failureText()),
            )
        }
    }

    private val GIT_REPOSITORY_SELECTORS = setOf(
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_COMMON_DIR",
        "GIT_INDEX_FILE",
        "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_CEILING_DIRECTORIES",
        "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    )
}

private data class OwnedRuntimeRecording(
    val mode: ProfilingMode,
    val path: Path,
    val recording: Recording,
)

private class RuntimeProfilingSession(
    recordings: List<OwnedRuntimeRecording>,
) : RuntimeProfilingOwnership {
    private var state: State = State.Open(recordings)

    @Synchronized
    override fun finish(): RuntimeProfilingFinish = when (val current = state) {
        is State.Finished -> current.result
        is State.Open -> current.recordings.finish().also { result -> state = State.Finished(result) }
    }

    private sealed interface State {
        data class Open(val recordings: List<OwnedRuntimeRecording>) : State

        data class Finished(val result: RuntimeProfilingFinish) : State
    }
}

private fun List<OwnedRuntimeRecording>.finish(): RuntimeProfilingFinish {
    val failed = linkedSetOf<Path>()
    asReversed().forEach { owned ->
        runCatching {
            try {
                if (owned.recording.state == RecordingState.RUNNING) owned.recording.stop()
            } finally {
                owned.recording.close()
            }
            check(Files.isRegularFile(owned.path) && Files.size(owned.path) > 0L)
        }.onFailure { failed.add(owned.path) }
    }
    return if (failed.isEmpty()) {
        RuntimeProfilingFinish.Completed
    } else {
        RuntimeProfilingFinish.Rejected(RuntimeProfilingFailure.FinalizationFailed(failed.toSet()))
    }
}

private fun List<OwnedRuntimeRecording>.closeAndDelete() {
    asReversed().forEach { owned ->
        runCatching { if (owned.recording.state == RecordingState.RUNNING) owned.recording.stop() }
        runCatching { owned.recording.close() }
        runCatching { Files.deleteIfExists(owned.path) }
    }
}

private fun Path.deleteIfEmpty() {
    runCatching {
        Files.newDirectoryStream(this).use { entries ->
            if (!entries.iterator().hasNext()) Files.deleteIfExists(this)
        }
    }
}

private fun Throwable.failureText(): String = message?.takeIf(String::isNotBlank) ?: javaClass.name

private data class RuntimeProfilingManifest(
    val runtimeImplementationVersion: String,
    val runtimeInstanceId: String,
    val workspaceRoot: String,
    val sourceHead: String,
    val startedAt: String,
    val durationSeconds: Long,
    val modes: List<String>,
    val artifacts: Map<String, String>,
) {
    fun toJson() = buildJsonObject {
        put("runtimeImplementationVersion", runtimeImplementationVersion)
        put("runtimeInstanceId", runtimeInstanceId)
        put("workspaceRoot", workspaceRoot)
        put("sourceHead", sourceHead)
        put("startedAt", startedAt)
        put("durationSeconds", durationSeconds)
        put("modes", buildJsonArray { modes.forEach { mode -> add(mode) } })
        put("artifacts", buildJsonObject { artifacts.forEach { (mode, path) -> put(mode, path) } })
    }
}
