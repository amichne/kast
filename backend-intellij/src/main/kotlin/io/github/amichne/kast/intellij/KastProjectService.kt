package io.github.amichne.kast.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RunningAnalysisServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class KastProjectService(
    private val project: Project,
) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val lifecycleLock = Any()

    @Volatile
    private var server: RunningAnalysisServer? = null

    fun start() {
        val workspaceRoot = project.basePath ?: return
        if (disposed.get()) {
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            val runningServer = AnalysisServer(
                backend = IntelliJAnalysisBackend(
                    project = project,
                    limits = ServerLimits(
                        maxResults = 500,
                        requestTimeoutMillis = 30_000,
                        maxConcurrentRequests = 4,
                    ),
                ),
                config = AnalysisServerConfig(
                    host = "127.0.0.1",
                    port = 0,
                    maxResults = 500,
                    maxConcurrentRequests = 4,
                ),
            ).start()
            val port = synchronized(lifecycleLock) {
                if (disposed.get()) {
                    null
                } else {
                    server = runningServer
                    runningServer.descriptor.port
                }
            }
            if (port == null) {
                runningServer.close()
                return@launch
            }

            println("kast IntelliJ backend started for $workspaceRoot on $port")
        }
    }

    override fun dispose() {
        disposed.set(true)
        val runningServer = synchronized(lifecycleLock) {
            server.also {
                server = null
            }
        }
        runningServer?.close()
        scope.cancel()
    }
}
