package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import kotlin.io.path.Path

data class StandaloneServerOptions(
    val workspaceRoot: Path,
    val sourceRoots: List<Path>,
    val classpathRoots: List<Path>,
    val moduleName: String,
    val host: String,
    val port: Int,
    val token: String?,
    val requestTimeoutMillis: Long,
    val maxResults: Int,
    val maxConcurrentRequests: Int,
) {
    companion object {
        fun parse(args: Array<String>): StandaloneServerOptions {
            val values = args.associate { argument ->
                val parts = argument.removePrefix("--").split("=", limit = 2)
                if (parts.size != 2) {
                    error("Arguments must use --key=value syntax: $argument")
                }
                parts[0] to parts[1]
            }
            return fromValues(values)
        }

        fun fromValues(values: Map<String, String>): StandaloneServerOptions {
            return StandaloneServerOptions(
                workspaceRoot = Path(
                    values["workspace-root"]
                        ?: System.getenv("KAST_WORKSPACE_ROOT")
                        ?: System.getProperty("user.dir"),
                ).toAbsolutePath().normalize(),
                sourceRoots = parsePathList(values["source-roots"]),
                classpathRoots = parsePathList(values["classpath"]),
                moduleName = values["module-name"] ?: "sources",
                host = values["host"] ?: "127.0.0.1",
                port = values["port"]?.toInt() ?: 0,
                token = values["token"] ?: System.getenv("KAST_TOKEN"),
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
        add("--host=$host")
        add("--port=$port")
        token?.let { add("--token=$it") }
        add("--request-timeout-ms=$requestTimeoutMillis")
        add("--max-results=$maxResults")
        add("--max-concurrent-requests=$maxConcurrentRequests")
    }
}

class RunningStandaloneRuntime(
    val server: RunningAnalysisServer,
    private val session: StandaloneAnalysisSession,
) : AutoCloseable {
    override fun close() {
        server.close()
        session.close()
    }

    fun await() {
        Thread.currentThread().join()
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
        val server = AnalysisServer(
            backend = backend,
            config = AnalysisServerConfig(
                host = options.host,
                port = options.port,
                token = options.token,
                requestTimeoutMillis = options.requestTimeoutMillis,
                maxResults = options.maxResults,
                maxConcurrentRequests = options.maxConcurrentRequests,
            ),
        ).start()

        return RunningStandaloneRuntime(
            server = server,
            session = session,
        )
    }

    fun run(options: StandaloneServerOptions) {
        val runtime = start(options)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runtime.close()
            },
        )

        println("kast standalone listening on ${runtime.server.descriptor.host}:${runtime.server.descriptor.port}")
        println("descriptor: ${runtime.server.descriptor}")
        runtime.await()
    }
}
