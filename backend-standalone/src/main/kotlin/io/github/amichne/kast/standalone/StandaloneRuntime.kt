package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.StandaloneServerOptions
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RunningAnalysisServer
import io.github.amichne.kast.standalone.profiling.ProfilingConfig
import io.github.amichne.kast.standalone.profiling.ProfilingManager
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope

internal class RunningStandaloneRuntime(
    val server: RunningAnalysisServer,
    private val session: StandaloneAnalysisSession,
    private val watcher: AutoCloseable,
    private val profiling: AutoCloseable? = null,
) : AutoCloseable {
    override fun close() {
        watcher.close()
        server.close()
        profiling?.close()
        session.close()
    }

    fun await() {
        server.await()
    }
}

object StandaloneRuntime {
    internal fun start(options: StandaloneServerOptions): RunningStandaloneRuntime {
        System.setProperty("java.awt.headless", "true")
        val config = KastConfig.load(
            workspaceRoot = options.workspaceRoot,
            overrides = KastConfigOverride(profiling = options.profilingOverride),
        )
        val telemetry = StandaloneTelemetry.fromConfig(options.workspaceRoot, config)
        val profiling = ProfilingManager(ProfilingConfig.fromConfig(config)).also { it.start() }
        val sessionLock: SessionLock = if (telemetry.isEnabled(StandaloneTelemetryScope.SESSION_LOCK)) {
            TelemetrySessionLock(telemetry)
        } else {
            ReentrantSessionLock()
        }
        val session = StandaloneAnalysisSession(
            workspaceRoot = options.workspaceRoot,
            sourceRoots = options.sourceRoots,
            classpathRoots = options.classpathRoots,
            moduleName = options.moduleName,
            config = config,
            analysisSessionLock = sessionLock,
            telemetry = telemetry,
        )
        val backend = StandaloneAnalysisBackend(
            workspaceRoot = options.workspaceRoot,
            limits = ServerLimits(
                maxResults = options.maxResults,
                requestTimeoutMillis = options.requestTimeoutMillis,
                maxConcurrentRequests = options.maxConcurrentRequests,
            ),
            session = session,
            telemetry = telemetry,
        )
        val watcher = WorkspaceRefreshWatcher(session, debounceMillis = config.watcher.debounceMillis.value)
        session.attachWorkspaceRefreshWatcher(watcher)
        val workspaceFileCount = session.moduleSpecs()
            .flatMap { it.sourceRoots }
            .sumOf { root ->
                root.toFile().walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .count()
            }
        val server = AnalysisServer(
            backend = backend,
            config = standaloneAnalysisServerConfig(options, config, workspaceFileCount),
        ).start()

        return RunningStandaloneRuntime(
            server = server,
            session = session,
            watcher = watcher,
            profiling = profiling,
        )
    }

    fun run(options: StandaloneServerOptions) {
        val runtime = start(options)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runtime.close()
            },
        )

        val descriptor = runtime.server.descriptor
        if (descriptor != null) {
            println("kast standalone listening on ${descriptor.socketPath}")
            println("descriptor: $descriptor")
        } else {
            println("kast standalone serving JSON-RPC on stdio")
        }
        runtime.await()
    }
}

internal fun standaloneAnalysisServerConfig(
    options: StandaloneServerOptions,
    config: KastConfig,
    workspaceFileCount: Int,
): AnalysisServerConfig = AnalysisServerConfig(
    transport = options.transport,
    requestTimeoutMillis = options.requestTimeoutMillis,
    maxResults = options.maxResults,
    maxConcurrentRequests = options.maxConcurrentRequests,
    descriptorDirectory = config.paths.descriptorDir.toPath(),
    workspaceFileCount = workspaceFileCount,
)
