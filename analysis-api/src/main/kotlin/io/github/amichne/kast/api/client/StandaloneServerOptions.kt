package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.AnalysisTransport

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

        fun fromValues(
            values: Map<String, String>,
            config: KastConfig? = null,
        ): StandaloneServerOptions {
            val workspaceRoot = Path(
                values["workspace-root"]
                    ?: System.getProperty("user.dir"),
            ).toAbsolutePath().normalize()
            val resolvedConfig = config ?: KastConfig.load(workspaceRoot)
            return StandaloneServerOptions(
                workspaceRoot = workspaceRoot,
                sourceRoots = parsePathList(values["source-roots"]),
                classpathRoots = parsePathList(values["classpath"]),
                moduleName = values["module-name"] ?: "sources",
                transport = when (values["transport"]?.lowercase()) {
                    "stdio" -> AnalysisTransport.Stdio
                    "tcp" -> AnalysisTransport.Tcp(
                        host = values["tcp-host"]
                            ?: error("tcp-host is required when transport=tcp"),
                        port = values["tcp-port"]?.toInt()
                            ?: error("tcp-port is required when transport=tcp"),
                    )
                    else -> AnalysisTransport.UnixDomainSocket(
                        socketPath = values["socket-path"]
                            ?.let(::Path)
                            ?.toAbsolutePath()
                            ?.normalize()
                            ?: defaultSocketPath(workspaceRoot),
                    )
                },
                requestTimeoutMillis = values["request-timeout-ms"]?.toLong() ?: resolvedConfig.server.requestTimeoutMillis,
                maxResults = values["max-results"]?.toInt() ?: resolvedConfig.server.maxResults,
                maxConcurrentRequests = values["max-concurrent-requests"]?.toInt() ?: resolvedConfig.server.maxConcurrentRequests,
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
            is AnalysisTransport.Tcp -> {
                add("--transport=tcp")
                add("--tcp-host=${transport.host}")
                add("--tcp-port=${transport.port}")
            }
        }
        add("--request-timeout-ms=$requestTimeoutMillis")
        add("--max-results=$maxResults")
        add("--max-concurrent-requests=$maxConcurrentRequests")
    }
}
