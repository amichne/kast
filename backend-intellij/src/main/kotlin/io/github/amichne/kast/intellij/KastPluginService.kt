package io.github.amichne.kast.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.indexstore.ReferenceIndexer
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RunningAnalysisServer
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val DEFAULT_MAX_RESULTS = 500
private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L
private const val DEFAULT_MAX_CONCURRENT_REQUESTS = 4

@Service(Service.Level.PROJECT)
internal class KastPluginService(
    private val project: Project,
) : Disposable {

    @Volatile
    private var runningServer: RunningAnalysisServer? = null

    private val indexingCancelled = AtomicBoolean(false)

    @Volatile
    private var indexingThread: Thread? = null

    @Volatile
    private var indexStore: SqliteSourceIndexStore? = null

    fun startServer() {
        if (runningServer != null) return
        val basePath = project.basePath ?: return
        val workspaceRoot = Path.of(basePath).toAbsolutePath().normalize()

        LOG.info("Starting kast intellij backend for workspace: $workspaceRoot")

        val limits = intellijServerLimits()

        val backend = KastPluginBackend(
            project = project,
            workspaceRoot = workspaceRoot,
            limits = limits,
            telemetry = IntelliJBackendTelemetry.fromEnvironment(workspaceRoot),
        )

        val socketPath = defaultSocketPath(workspaceRoot)
        val config = AnalysisServerConfig(
            transport = AnalysisTransport.UnixDomainSocket(socketPath),
            requestTimeoutMillis = limits.requestTimeoutMillis,
            maxResults = limits.maxResults,
            maxConcurrentRequests = limits.maxConcurrentRequests,
        )

        val server = AnalysisServer(backend, config)
        runningServer = server.start()
        startReferenceIndexing(workspaceRoot)

        LOG.info("Kast intellij backend started on socket: $socketPath")
    }

    override fun dispose() {
        cancelReferenceIndexing()
        runningServer?.let { server ->
            LOG.info("Shutting down kast intellij backend")
            runCatching { server.close() }
                .onFailure { LOG.warn("Error closing kast server", it) }
            runningServer = null
        }
    }

    private fun startReferenceIndexing(workspaceRoot: Path) {
        if (indexingThread != null) return
        indexingCancelled.set(false)
        val store = SqliteSourceIndexStore(workspaceRoot)
        indexStore = store
        DumbService.getInstance(project).runWhenSmart {
            if (indexingCancelled.get() || project.isDisposed) return@runWhenSmart
            indexingThread = thread(
                start = true,
                isDaemon = true,
                name = "kast-intellij-reference-indexer",
            ) {
                val environment = IntelliJReferenceIndexEnvironment(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    cancelled = { indexingCancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed },
                )
                runCatching {
                    store.ensureSchema()
                    val currentFilePaths = environment.allFilePaths()
                    store.removeReferencesOutsideSources(currentFilePaths)
                    ReferenceIndexer(store).indexReferences(
                        filePaths = currentFilePaths,
                        referenceScanner = PsiReferenceScanner(environment)::scanFileReferences,
                        isCancelled = environment::isCancelled,
                    )
                }.onSuccess {
                    if (!indexingCancelled.get()) {
                        LOG.info("Kast IntelliJ reference index completed")
                    }
                }.onFailure { error ->
                    if (!indexingCancelled.get()) {
                        LOG.warn("Kast IntelliJ reference index failed", error)
                    }
                }
            }
        }
    }

    private fun cancelReferenceIndexing() {
        indexingCancelled.set(true)
        indexingThread?.interrupt()
        if (!ApplicationManager.getApplication().isDispatchThread) {
            indexingThread?.join(2_000)
        }
        indexingThread = null
        indexStore?.let { store ->
            runCatching { store.close() }
                .onFailure { LOG.warn("Error closing kast reference index store", it) }
        }
        indexStore = null
    }

    companion object {
        private val LOG = Logger.getInstance(KastPluginService::class.java)
    }
}

internal fun intellijServerLimits(
    getenv: (String) -> String? = System::getenv,
): ServerLimits = ServerLimits(
    maxConcurrentRequests = (getenv("KAST_INTELLIJ_MAX_CONCURRENT")?.toIntOrNull() ?: DEFAULT_MAX_CONCURRENT_REQUESTS).coerceAtLeast(1),
    requestTimeoutMillis = getenv("KAST_INTELLIJ_TIMEOUT_MS")?.toLongOrNull() ?: DEFAULT_REQUEST_TIMEOUT_MILLIS,
    maxResults = getenv("KAST_INTELLIJ_MAX_RESULTS")?.toIntOrNull() ?: DEFAULT_MAX_RESULTS,
)
