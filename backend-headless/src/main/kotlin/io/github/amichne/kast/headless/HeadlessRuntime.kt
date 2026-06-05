package io.github.amichne.kast.headless

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.PathsConfigOverride
import io.github.amichne.kast.api.client.ProfilingConfigOverride
import io.github.amichne.kast.api.client.ServerLaunchOptions
import io.github.amichne.kast.api.client.fields.PathsCacheDir
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.client.fields.PathsLogsDir
import io.github.amichne.kast.api.client.fields.PathsSocketDir
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.idea.KastIdeaBackendRuntime
import io.github.amichne.kast.idea.RunningKastIdeaBackend
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class HeadlessServerOptions(
    val serverOptions: ServerLaunchOptions,
    val smokeOnly: Boolean = false,
) {
    companion object {
        fun parseStarterArgs(args: List<String>): HeadlessServerOptions {
            val normalizedArgs = args.dropCommandName()
            val smokeOnly = normalizedArgs.any { it == "--smoke-only" }
            val serverArgs = normalizedArgs
                .filterNot { it == "--smoke-only" }
                .filterNot { it.startsWith(HeadlessBootstrapOptions.IDEA_HOME_PREFIX) }
                .toTypedArray()
            return HeadlessServerOptions(
                serverOptions = ServerLaunchOptions.parse(serverArgs),
                smokeOnly = smokeOnly,
            )
        }

        private fun List<String>.dropCommandName(): List<String> =
            if (firstOrNull() == HeadlessApplicationStarter.COMMAND_NAME) drop(1) else this
    }
}

data class HeadlessBootstrapOptions(
    val ideaHome: Path? = null,
) {
    companion object {
        const val IDEA_HOME_PREFIX = "--idea-home="

        fun parse(args: Array<String>): HeadlessBootstrapOptions {
            val ideaHome = args
                .firstOrNull { it.startsWith(IDEA_HOME_PREFIX) }
                ?.removePrefix(IDEA_HOME_PREFIX)
                ?.takeIf(String::isNotBlank)
                ?.let { Path.of(it).toAbsolutePath().normalize() }
            return HeadlessBootstrapOptions(ideaHome = ideaHome)
        }
    }
}

class RunningHeadlessRuntime internal constructor(
    val backendRuntime: RunningKastIdeaBackend,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backendRuntime.close()
    }

    fun await() {
        backendRuntime.await()
    }
}

object HeadlessRuntime {
    fun configureSystemProperties(options: HeadlessBootstrapOptions = HeadlessBootstrapOptions()) {
        System.setProperty("java.awt.headless", "true")
        System.setProperty("idea.is.internal", "true")
        System.setProperty("kast.idea.autostart", "false")
        options.ideaHome?.let { ideaHome ->
            System.setProperty("idea.home.path", ideaHome.toString())
        }
    }

    fun ideaMainArgs(args: Array<String>): Array<String> =
        arrayOf(
            HeadlessApplicationStarter.COMMAND_NAME,
            *args.filterNot { it.startsWith(HeadlessBootstrapOptions.IDEA_HOME_PREFIX) }.toTypedArray(),
        )

    fun start(
        options: HeadlessServerOptions,
        projectOpener: HeadlessProjectOpener = HeadlessProjectOpener(),
    ): RunningHeadlessRuntime {
        configureSystemProperties()
        val serverOptions = options.serverOptions
        val workspaceRoot = serverOptions.workspaceRoot
        val project = projectOpener.openProject(workspaceRoot)
        val config = KastConfig.load(
            workspaceRoot = workspaceRoot,
            overrides = HeadlessConfigProperties.configOverride(serverOptions.profilingOverride),
        )
        val backendRuntime = KastIdeaBackendRuntime.start(
            project = project,
            workspaceRoot = workspaceRoot,
            transport = serverOptions.transport,
            config = config,
            backendName = "headless",
        )
        val status = runBlocking { backendRuntime.backend.runtimeStatus() }
        check(status.backendName == "headless") {
            "Headless backend started with unexpected backend name: ${status.backendName}"
        }
        return RunningHeadlessRuntime(backendRuntime)
    }

    fun run(options: HeadlessServerOptions) {
        val runtime = start(options)

        val projectName = runBlocking { runtime.backendRuntime.backend.runtimeStatus().workspaceRoot }
        println("Project opened and indexes ready: $projectName")
        if (options.smokeOnly) {
            runtime.close()
            exitProcess(0)
        }

        Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })

        val descriptor = runtime.backendRuntime.server.descriptor
        when (val transport = options.serverOptions.transport) {
            is AnalysisTransport.UnixDomainSocket -> {
                println("kast headless listening on ${transport.socketPath}")
                println("descriptor: $descriptor")
            }
            AnalysisTransport.Stdio -> println("kast headless serving JSON-RPC on stdio")
            is AnalysisTransport.Tcp -> println("kast headless listening on ${transport.host}:${transport.port}")
        }
        runtime.await()
    }
}

internal object HeadlessConfigProperties {
    const val CACHE_DIR = "kast.headless.paths.cacheDir"
    const val LOGS_DIR = "kast.headless.paths.logsDir"
    const val DESCRIPTOR_DIR = "kast.headless.paths.descriptorDir"
    const val SOCKET_DIR = "kast.headless.paths.socketDir"

    fun configOverride(profilingOverride: ProfilingConfigOverride?): KastConfigOverride = KastConfigOverride(
        profiling = profilingOverride,
        paths = pathsOverride(),
    )

    private fun pathsOverride(): PathsConfigOverride? {
        val cacheDir = pathProperty(CACHE_DIR)?.let(::PathsCacheDir)
        val logsDir = pathProperty(LOGS_DIR)?.let(::PathsLogsDir)
        val descriptorDir = pathProperty(DESCRIPTOR_DIR)?.let(::PathsDescriptorDir)
        val socketDir = pathProperty(SOCKET_DIR)?.let(::PathsSocketDir)
        if (cacheDir == null && logsDir == null && descriptorDir == null && socketDir == null) {
            return null
        }
        return PathsConfigOverride(
            cacheDir = cacheDir,
            logsDir = logsDir,
            descriptorDir = descriptorDir,
            socketDir = socketDir,
        )
    }

    private fun pathProperty(name: String): String? = System.getProperty(name)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}
