package io.github.amichne.kast.indexer

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.ProfilingConfig
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.ServerLaunchOptions
import io.github.amichne.kast.api.client.fields.ProfilingModeFailure
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.idea.IndexerServerRuntime
import io.github.amichne.kast.idea.RunningIndexer
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridge
import io.github.amichne.kast.indexer.project.ProjectOpener
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class IndexerServerOptions(
    val serverOptions: ServerLaunchOptions,
    val runtimeConfig: KastConfig? = null,
    val smokeOnly: Boolean = false,
) {
    companion object {
        const val RUNTIME_CONFIG_FILE_PREFIX = "--runtime-config-file="

        fun parseStarterArgs(args: List<String>): IndexerServerOptions {
            val normalizedArgs = args.dropCommandName()
            val smokeOnly = normalizedArgs.any { it == "--smoke-only" }
            val runtimeConfig = normalizedArgs.runtimeConfigFile()?.let(KastConfig::loadResolvedJson)
            val serverArgs = normalizedArgs
                .filterNot { it == "--smoke-only" }
                .filterNot { it.startsWith(IDEA_HOME_PREFIX) }
                .filterNot { it.startsWith(RUNTIME_CONFIG_FILE_PREFIX) }
                .toTypedArray()
            val serverOptions = ServerLaunchOptions.parse(
                args = serverArgs,
                config = runtimeConfig,
            )
            return IndexerServerOptions(
                serverOptions = serverOptions,
                runtimeConfig = runtimeConfig?.withOverrides(
                    KastConfigOverride(profiling = serverOptions.profilingOverride),
                ),
                smokeOnly = smokeOnly,
            )
        }

        private fun List<String>.dropCommandName(): List<String> =
            if (firstOrNull() == KAST_INDEXER_COMMAND_NAME) drop(1) else this

        private fun List<String>.runtimeConfigFile(): Path? = firstOrNull { it.startsWith(RUNTIME_CONFIG_FILE_PREFIX) }
            ?.removePrefix(RUNTIME_CONFIG_FILE_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }

        private const val IDEA_HOME_PREFIX = "--idea-home="
    }
}

internal data class RuntimeProfilingLaunch(
    val config: ProfilingConfig,
    val logsDirectory: Path,
    val workspaceRoot: Path,
    val runtimeVersion: RuntimeImplementationVersion,
    val runtimeInstanceId: RuntimeInstanceId,
    val clock: Clock = Clock.systemUTC(),
)

internal sealed interface RuntimeProfilingStart {
    data object Disabled : RuntimeProfilingStart

    data class Started(val ownership: RuntimeProfilingOwnership) : RuntimeProfilingStart

    data class Rejected(val failure: RuntimeProfilingFailure) : RuntimeProfilingStart
}

internal sealed interface RuntimeProfilingOwnership {
    /**
     * Proof transition: `RuntimeProfilingOwnership -> RuntimeProfilingFinish`.
     *
     * Establishes that every owned recording is closed and each requested
     * artifact is a non-empty regular file, or returns the closed finalization
     * failure. Raw JFR resources remain confined to the JFR boundary.
     */
    fun finish(): RuntimeProfilingFinish

    data object Disabled : RuntimeProfilingOwnership {
        override fun finish(): RuntimeProfilingFinish = RuntimeProfilingFinish.Completed
    }
}

internal sealed interface RuntimeProfilingFinish {
    data object Completed : RuntimeProfilingFinish

    data class Rejected(val failure: RuntimeProfilingFailure.FinalizationFailed) : RuntimeProfilingFinish
}

internal sealed interface RuntimeProfilingFailure {
    data class InvalidModes(val failure: ProfilingModeFailure) : RuntimeProfilingFailure

    data class InvalidDuration(val seconds: Long) : RuntimeProfilingFailure

    data class InvalidOutputDirectory(val value: String) : RuntimeProfilingFailure

    data class WorkspaceUnavailable(val path: Path, val reason: String) : RuntimeProfilingFailure

    data class SourceHeadUnavailable(val workspace: Path, val reason: String) : RuntimeProfilingFailure

    data class ArtifactPreparationFailed(val path: Path, val reason: String) : RuntimeProfilingFailure

    data class RecorderStartFailed(val path: Path, val reason: String) : RuntimeProfilingFailure

    data class FinalizationFailed(val artifacts: Set<Path>) : RuntimeProfilingFailure {
        init {
            require(artifacts.isNotEmpty()) { "Failed profiling artifacts must not be empty" }
        }
    }
}

internal class RuntimeProfilingBoundaryException(
    val failure: RuntimeProfilingFailure,
) : IllegalStateException(failure.message())

private fun RuntimeProfilingFailure.message(): String = when (this) {
    is RuntimeProfilingFailure.InvalidModes -> "Invalid profiling modes: $failure"
    is RuntimeProfilingFailure.InvalidDuration -> "Profiling duration must be positive: $seconds"
    is RuntimeProfilingFailure.InvalidOutputDirectory -> "Profiling output directory must be absolute: $value"
    is RuntimeProfilingFailure.WorkspaceUnavailable -> "Profiling workspace is unavailable at $path: $reason"
    is RuntimeProfilingFailure.SourceHeadUnavailable -> "Profiling source HEAD is unavailable at $workspace: $reason"
    is RuntimeProfilingFailure.ArtifactPreparationFailed -> "Cannot prepare profiling artifacts at $path: $reason"
    is RuntimeProfilingFailure.RecorderStartFailed -> "Cannot start runtime profiling at $path: $reason"
    is RuntimeProfilingFailure.FinalizationFailed -> "Cannot finalize profiling artifacts: ${artifacts.joinToString()}"
}

class RunningKastIndexer internal constructor(
    val indexerRuntime: RunningIndexer,
    private val profiling: RuntimeProfilingOwnership,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { indexerRuntime.close() }.fold(
            onSuccess = {
                when (val result = profiling.finish()) {
                    RuntimeProfilingFinish.Completed -> Unit
                    is RuntimeProfilingFinish.Rejected -> throw RuntimeProfilingBoundaryException(result.failure)
                }
            },
            onFailure = { failure ->
                when (val result = profiling.finish()) {
                    RuntimeProfilingFinish.Completed -> Unit
                    is RuntimeProfilingFinish.Rejected -> {
                        failure.addSuppressed(RuntimeProfilingBoundaryException(result.failure))
                    }
                }
                throw failure
            },
        )
    }

    fun await() {
        indexerRuntime.await()
    }
}

object KastIndexerRuntime {
    fun configureSystemProperties(ideaHome: Path? = null) {
        System.setProperty("java.awt.headless", "true")
        System.setProperty("idea.is.internal", "true")
        ideaHome?.let { System.setProperty("idea.home.path", it.toString()) }
    }

    fun ideaMainArgs(args: Array<String>): Array<String> =
        arrayOf(
            KAST_INDEXER_COMMAND_NAME,
            *args.filterNot { it.startsWith("--idea-home=") }.toTypedArray(),
        )

    fun start(
        options: IndexerServerOptions,
        projectOpener: ProjectOpener = ProjectOpener(),
    ): RunningKastIndexer {
        configureSystemProperties()
        val serverOptions = options.serverOptions
        val workspaceRoot = serverOptions.workspaceRoot
        val config = options.runtimeConfig ?: KastConfig.load(
            workspaceRoot = workspaceRoot,
            overrides = KastConfigOverride(profiling = serverOptions.profilingOverride),
        )
        val registrationProof = serverOptions.linkedWorktreeLaunchClaim?.let { claim ->
            GitWorktreeRegistrationProof.capture(workspaceRoot, claim)
        }
        val runtimeInstanceId = serverOptions.runtimeInstanceId ?: RuntimeInstanceId.create()
        val profiling = when (
            val started = KastRuntimeProfiling.start(
                RuntimeProfilingLaunch(
                    config = config.profiling,
                    logsDirectory = Path.of(config.paths.logsDir.value),
                    workspaceRoot = workspaceRoot,
                    runtimeVersion = RuntimeImplementationVersion(KastIndexerBackend.INDEXER_VERSION),
                    runtimeInstanceId = runtimeInstanceId,
                ),
            )
        ) {
            RuntimeProfilingStart.Disabled -> RuntimeProfilingOwnership.Disabled
            is RuntimeProfilingStart.Started -> started.ownership
            is RuntimeProfilingStart.Rejected -> throw RuntimeProfilingBoundaryException(started.failure)
        }
        try {
            GradleProjectImportBridge.configureIndexerApplication()
            val project = projectOpener.openProject(workspaceRoot)
            val indexerRuntime = IndexerServerRuntime.startWithRegistrationProof(
                project = project,
                workspaceRoot = workspaceRoot,
                transport = serverOptions.transport,
                config = config,
                registrationProof = registrationProof,
                runtimeInstanceId = runtimeInstanceId,
            )
            val status = runBlocking { indexerRuntime.backend.runtimeStatus() }
            check(status.backendName == "indexer") {
                "Kast indexer started with unexpected runtime name: ${status.backendName}"
            }
            return RunningKastIndexer(indexerRuntime, profiling)
        } catch (failure: Throwable) {
            when (val result = profiling.finish()) {
                RuntimeProfilingFinish.Completed -> Unit
                is RuntimeProfilingFinish.Rejected -> {
                    failure.addSuppressed(RuntimeProfilingBoundaryException(result.failure))
                }
            }
            throw failure
        }
    }

    fun run(options: IndexerServerOptions) {
        val runtime = start(options)

        val projectName = runBlocking { runtime.indexerRuntime.backend.runtimeStatus().workspaceRoot }
        println("Project opened and indexes ready: $projectName")
        if (options.smokeOnly) {
            runtime.close()
            exitProcess(0)
        }

        Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })

        val descriptor = runtime.indexerRuntime.server.descriptor
        when (val transport = options.serverOptions.transport) {
            is AnalysisTransport.UnixDomainSocket -> {
                println("kast indexer listening on ${transport.socketPath}")
                println("descriptor: $descriptor")
            }
            AnalysisTransport.Stdio -> println("kast indexer serving JSON-RPC on stdio")
            is AnalysisTransport.Tcp -> println("kast indexer listening on ${transport.host}:${transport.port}")
        }
        runtime.await()
    }
}
