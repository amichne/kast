package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RunningKastIntelliJBackend internal constructor(
    val backend: AnalysisBackend,
    val server: RunningAnalysisServer,
    private val projectIndexing: KastIntelliJProjectIndexing,
) : AutoCloseable {
    override fun close() {
        projectIndexing.cancel()
        server.close()
    }

    fun await() {
        server.await()
    }
}

object KastIntelliJBackendRuntime {
    fun start(
        project: Project,
        workspaceRoot: Path,
        socketPath: Path = defaultSocketPath(workspaceRoot),
        config: KastConfig = KastConfig.load(workspaceRoot),
        backendName: String? = null,
    ): RunningKastIntelliJBackend = start(
        project = project,
        workspaceRoot = workspaceRoot,
        transport = AnalysisTransport.UnixDomainSocket(socketPath),
        config = config,
        backendName = backendName,
    )

    fun start(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig = KastConfig.load(workspaceRoot),
        backendName: String? = null,
    ): RunningKastIntelliJBackend {
        val limits = intellijServerLimits(config)
        val backend = KastPluginBackend(
            project = project,
            workspaceRoot = workspaceRoot,
            limits = limits,
            telemetry = IntelliJBackendTelemetry.fromConfig(workspaceRoot, config),
            backendName = backendName,
        )
        val server = AnalysisServer(
            backend = backend,
            config = intellijAnalysisServerConfig(transport, limits, config),
        ).start()
        val projectIndexing = KastIntelliJProjectIndexing(project, workspaceRoot, config).also { it.start() }
        return RunningKastIntelliJBackend(
            backend = backend,
            server = server,
            projectIndexing = projectIndexing,
        )
    }
}

internal class KastIntelliJProjectIndexing(
    private val project: Project,
    private val workspaceRoot: Path,
    private val config: KastConfig,
) {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var indexingThread: Thread? = null

    @Volatile
    private var indexStore: SqliteSourceIndexStore? = null

    fun start() {
        if (indexingThread != null) return
        cancelled.set(false)
        DumbService.getInstance(project).runWhenSmart {
            if (cancelled.get() || project.isDisposed) return@runWhenSmart
            indexingThread = thread(
                start = true,
                isDaemon = true,
                name = "kast-intellij-project-indexer",
            ) {
                runCatching {
                    runCatching {
                        SourceIndexHydrator().hydrate(workspaceRoot, config.indexing.remote)
                    }.onFailure { error ->
                        LOG.warn("Kast IntelliJ remote source index hydration failed", error)
                    }
                    val store = SqliteSourceIndexStore(workspaceRoot)
                    indexStore = store
                    IntelliJProjectIndexer(
                        project = project,
                        workspaceRoot = workspaceRoot,
                        store = store,
                        cancelled = { cancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed },
                    ).indexProject(config)
                }.onSuccess {
                    if (!cancelled.get()) {
                        LOG.info("Kast IntelliJ project index completed")
                    }
                }.onFailure { error ->
                    if (!cancelled.get()) {
                        LOG.warn("Kast IntelliJ project index failed", error)
                    }
                }
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        indexingThread?.interrupt()
        if (!ApplicationManager.getApplication().isDispatchThread) {
            indexingThread?.join(2_000)
        }
        indexingThread = null
        indexStore?.let { store ->
            runCatching { store.close() }
                .onFailure { LOG.warn("Error closing kast project index store", it) }
        }
        indexStore = null
    }

    companion object {
        private val LOG = Logger.getInstance(KastIntelliJProjectIndexing::class.java)
    }
}
