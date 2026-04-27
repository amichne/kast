package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.client.StandaloneServerOptions
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RunningAnalysisServer
import java.lang.Runtime as JvmRuntime

internal class RunningRuntime(
    val server: RunningAnalysisServer,
    private val session: AnalysisSession,
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

object Runtime {
    internal fun start(options: StandaloneServerOptions): RunningRuntime {
        System.setProperty("java.awt.headless", "true")
        val phasedDiscoveryResult = discoverWorkspaceLayoutPhased(
            workspaceRoot = options.workspaceRoot,
            sourceRoots = options.sourceRoots,
            classpathRoots = options.classpathRoots,
            moduleName = options.moduleName,
        )
        val session = AnalysisSession(
            workspaceRoot = options.workspaceRoot,
            sourceRoots = options.sourceRoots,
            classpathRoots = options.classpathRoots,
            moduleName = options.moduleName,
            phasedDiscoveryResult = phasedDiscoveryResult,
        )
        val backend = AnalysisBackend(
            workspaceRoot = options.workspaceRoot,
            limits = ServerLimits(
                maxResults = options.maxResults,
                requestTimeoutMillis = options.requestTimeoutMillis,
                maxConcurrentRequests = options.maxConcurrentRequests,
            ),
            session = session,
        )
        val watcher = WorkspaceRefreshWatcher(session)
        session.attachWorkspaceRefreshWatcher(watcher)
        val server = AnalysisServer(
            backend = backend,
            config = AnalysisServerConfig(
                transport = options.transport,
                requestTimeoutMillis = options.requestTimeoutMillis,
                maxResults = options.maxResults,
                maxConcurrentRequests = options.maxConcurrentRequests,
            ),
        ).start()

        return RunningRuntime(
            server = server,
            session = session,
            watcher = watcher,
        )
    }

    fun run(options: StandaloneServerOptions) {
        val runtime = start(options)
        JvmRuntime.getRuntime().addShutdownHook(
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
