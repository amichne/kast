package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.AnalysisTransport
import io.github.amichne.kast.server.RunningAnalysisServer
import io.github.amichne.kast.server.defaultSocketPath
import java.nio.file.Path
import kotlin.io.path.Path

data class StandaloneServerOptions(
    val workspaceRoot: Path,
    val sourceRoots: List<Path>,
    val classpathRoots: List<Path>,
    val moduleName: String,
    val transport: AnalysisTransport,
    val requestTimeoutMillis: Long,
    val maxResults: Int,
    val maxConcurrentRequests: Int,
) {
    companion object {
        fun parse(args: Array<String>): StandaloneServerOptions {
            val values = mutableMapOf<String, String>()
            args.forEach { argument ->
                if (argument == "--stdio") {
                    values["transport"] = "stdio"
                    return@forEach
                }
                val parts = argument.removePrefix("--").split("=", limit = 2)
                if (parts.size != 2) {
                    error("Arguments must use --key=value syntax: $argument")
                }
                values[parts[0]] = parts[1]
            }
            return fromValues(values)
        }

        fun fromValues(values: Map<String, String>): StandaloneServerOptions {
            val workspaceRoot = Path(
                values["workspace-root"]
                    ?: System.getenv("KAST_WORKSPACE_ROOT")
                    ?: System.getProperty("user.dir"),
            ).toAbsolutePath().normalize()
            return StandaloneServerOptions(
                workspaceRoot = workspaceRoot,
                sourceRoots = parsePathList(values["source-roots"]),
                classpathRoots = parsePathList(values["classpath"]),
                moduleName = values["module-name"] ?: "sources",
                transport = when (values["transport"]?.lowercase()) {
                    "stdio" -> AnalysisTransport.Stdio
                    else -> AnalysisTransport.UnixDomainSocket(
                        socketPath = values["socket-path"]
                            ?.let(::Path)
                            ?.toAbsolutePath()
                            ?.normalize()
                            ?: defaultSocketPath(workspaceRoot),
                    )
                },
                requestTimeoutMillis = values["request-timeout-ms"]?.toLong() ?: 30_000L,
                maxResults = values["max-results"]?.toInt() ?: 500,
                maxConcurrentRequests = values["max-concurrent-requests"]?.toInt() ?: 4,
            )
        }

        private fun parsePathList(value: String?): List<Path> = value
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { entry -> Path(entry).toAbsolutePath().normalize() }
            ?: emptyList()
    }

    fun toCliArguments(): List<String> = buildList {
        add("--workspace-root=$workspaceRoot")
        if (sourceRoots.isNotEmpty()) {
            add("--source-roots=${sourceRoots.joinToString(",")}")
        }
        if (classpathRoots.isNotEmpty()) {
            add("--classpath=${classpathRoots.joinToString(",")}")
        }
        add("--module-name=$moduleName")
        when (val transport = transport) {
            is AnalysisTransport.UnixDomainSocket -> add("--socket-path=${transport.socketPath}")
            AnalysisTransport.Stdio -> add("--stdio")
        }
        add("--request-timeout-ms=$requestTimeoutMillis")
        add("--max-results=$maxResults")
        add("--max-concurrent-requests=$maxConcurrentRequests")
    }
}

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
