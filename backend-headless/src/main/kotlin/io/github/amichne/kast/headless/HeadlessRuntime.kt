package io.github.amichne.kast.headless

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.ServerLaunchOptions
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.idea.KastIdeaBackendRuntime
import io.github.amichne.kast.idea.KastProjectOpenProfileAutoInit
import io.github.amichne.kast.idea.RunningKastIdeaBackend
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class HeadlessServerOptions(
    val serverOptions: ServerLaunchOptions,
    val runtimeConfig: KastConfig? = null,
    val smokeOnly: Boolean = false,
) {
    companion object {
        const val RUNTIME_CONFIG_FILE_PREFIX = "--runtime-config-file="

        fun parseStarterArgs(args: List<String>): HeadlessServerOptions {
            val normalizedArgs = args.dropCommandName()
            val smokeOnly = normalizedArgs.any { it == "--smoke-only" }
            val runtimeConfig = normalizedArgs.runtimeConfigFile()?.let(KastConfig::loadResolvedJson)
            val serverArgs = normalizedArgs
                .filterNot { it == "--smoke-only" }
                .filterNot { it.startsWith(HeadlessBootstrapOptions.IDEA_HOME_PREFIX) }
                .filterNot { it.startsWith(RUNTIME_CONFIG_FILE_PREFIX) }
                .toTypedArray()
            val serverOptions = ServerLaunchOptions.parse(
                args = serverArgs,
                config = runtimeConfig,
            )
            return HeadlessServerOptions(
                serverOptions = serverOptions,
                runtimeConfig = runtimeConfig?.withOverrides(
                    KastConfigOverride(profiling = serverOptions.profilingOverride),
                ),
                smokeOnly = smokeOnly,
            )
        }

        private fun List<String>.dropCommandName(): List<String> =
            if (firstOrNull() == HeadlessApplicationStarter.COMMAND_NAME) drop(1) else this

        private fun List<String>.runtimeConfigFile(): Path? = firstOrNull { it.startsWith(RUNTIME_CONFIG_FILE_PREFIX) }
            ?.removePrefix(RUNTIME_CONFIG_FILE_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
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
        HeadlessGradleProjectImportBridge.configureHeadlessApplication()
        val project = projectOpener.openProject(workspaceRoot)
        val config = options.runtimeConfig ?: KastConfig.load(
            workspaceRoot = workspaceRoot,
            overrides = KastConfigOverride(profiling = serverOptions.profilingOverride),
        )
        val autoInitResult = KastProjectOpenProfileAutoInit.execute(workspaceRoot, config)
        KastProjectOpenProfileAutoInit.log(autoInitResult)
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
