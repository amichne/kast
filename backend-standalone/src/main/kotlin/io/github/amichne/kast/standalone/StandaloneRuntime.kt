package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.StandaloneServerOptions
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RunningAnalysisServer

class RunningStandaloneRuntime(
    val server: RunningAnalysisServer,
    private val session: StandaloneAnalysisSession,
    private val watcher: AutoCloseable,
) : AutoCloseable {
    override fun close() {
        watcher.close()
        server.close()
        session.close()
    }

    fun await() {
        server.await()
    }
}

object StandaloneRuntime {
    fun start(options: StandaloneServerOptions): RunningStandaloneRuntime {
        System.setProperty("java.awt.headless", "true")
        val session = StandaloneAnalysisSession(
            workspaceRoot = options.workspaceRoot,
            sourceRoots = options.sourceRoots,
            classpathRoots = options.classpathRoots,
            moduleName = options.moduleName,
        )
        val backend = StandaloneAnalysisBackend(
            workspaceRoot = options.workspaceRoot,
            limits = ServerLimits(
                maxResults = options.maxResults,
                requestTimeoutMillis = options.requestTimeoutMillis,
                maxConcurrentRequests = options.maxConcurrentRequests,
            ),
            session = session,
        )
        val watcher = WorkspaceRefreshWatcher(session)
        val server = AnalysisServer(
            backend = backend,
            config = AnalysisServerConfig(
                transport = options.transport,
                requestTimeoutMillis = options.requestTimeoutMillis,
                maxResults = options.maxResults,
                maxConcurrentRequests = options.maxConcurrentRequests,
            ),
        ).start()

        return RunningStandaloneRuntime(
            server = server,
            session = session,
            watcher = watcher,
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
