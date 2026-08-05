package io.github.amichne.kast.indexer

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.ServerLaunchOptions
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.idea.IndexerServerRuntime
import io.github.amichne.kast.idea.RunningIndexer
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridge
import io.github.amichne.kast.indexer.project.ProjectOpener
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class IndexerServerOptions(
    val serverOptions: ServerLaunchOptions,
    val runtimeConfig: KastConfig? = null,
    val smokeOnly: Boolean = false,
) {
    companion object {
        const val RUNTIME_CONFIG_FILE_PREFIX = "--runtime-config-file="

        fun parseStarterArgs(args: List<String>): IndexerServerOptions {
            val normalizedArgs = args.dropCommandName()
            val smokeOnly = normalizedArgs.any { it == "--smoke-only" }
            val runtimeConfig = normalizedArgs.runtimeConfigFile()?.let(KastConfig::loadResolvedJson)
            val serverArgs = normalizedArgs
                .filterNot { it == "--smoke-only" }
                .filterNot { it.startsWith(IDEA_HOME_PREFIX) }
                .filterNot { it.startsWith(RUNTIME_CONFIG_FILE_PREFIX) }
                .toTypedArray()
            val serverOptions = ServerLaunchOptions.parse(
                args = serverArgs,
                config = runtimeConfig,
            )
            return IndexerServerOptions(
                serverOptions = serverOptions,
                runtimeConfig = runtimeConfig?.withOverrides(
                    KastConfigOverride(profiling = serverOptions.profilingOverride),
                ),
                smokeOnly = smokeOnly,
            )
        }

        private fun List<String>.dropCommandName(): List<String> =
            if (firstOrNull() == KAST_INDEXER_COMMAND_NAME) drop(1) else this

        private fun List<String>.runtimeConfigFile(): Path? = firstOrNull { it.startsWith(RUNTIME_CONFIG_FILE_PREFIX) }
            ?.removePrefix(RUNTIME_CONFIG_FILE_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }

        private const val IDEA_HOME_PREFIX = "--idea-home="
    }
}

class RunningKastIndexer internal constructor(
    val indexerRuntime: RunningIndexer,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        indexerRuntime.close()
    }

    fun await() {
        indexerRuntime.await()
    }
}

object KastIndexerRuntime {
    fun configureSystemProperties(ideaHome: Path? = null) {
        System.setProperty("java.awt.headless", "true")
        System.setProperty("idea.is.internal", "true")
        ideaHome?.let { System.setProperty("idea.home.path", it.toString()) }
    }

    fun ideaMainArgs(args: Array<String>): Array<String> =
        arrayOf(
            KAST_INDEXER_COMMAND_NAME,
            *args.filterNot { it.startsWith("--idea-home=") }.toTypedArray(),
        )

    fun start(
        options: IndexerServerOptions,
        projectOpener: ProjectOpener = ProjectOpener(),
    ): RunningKastIndexer {
        configureSystemProperties()
        val serverOptions = options.serverOptions
        val workspaceRoot = serverOptions.workspaceRoot
        val registrationProof = serverOptions.linkedWorktreeLaunchClaim?.let { claim ->
            GitWorktreeRegistrationProof.capture(workspaceRoot, claim)
        }
        GradleProjectImportBridge.configureIndexerApplication()
        val project = projectOpener.openProject(workspaceRoot)
        val config = options.runtimeConfig ?: KastConfig.load(
            workspaceRoot = workspaceRoot,
            overrides = KastConfigOverride(profiling = serverOptions.profilingOverride),
        )
        val indexerRuntime = IndexerServerRuntime.startWithRegistrationProof(
            project = project,
            workspaceRoot = workspaceRoot,
            transport = serverOptions.transport,
            config = config,
            registrationProof = registrationProof,
        )
        val status = runBlocking { indexerRuntime.backend.runtimeStatus() }
        check(status.backendName == "indexer") {
            "Kast indexer started with unexpected runtime name: ${status.backendName}"
        }
        return RunningKastIndexer(indexerRuntime)
    }

    fun run(options: IndexerServerOptions) {
        val runtime = start(options)

        val projectName = runBlocking { runtime.indexerRuntime.backend.runtimeStatus().workspaceRoot }
        println("Project opened and indexes ready: $projectName")
        if (options.smokeOnly) {
            runtime.close()
            exitProcess(0)
        }

        Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })

        val descriptor = runtime.indexerRuntime.server.descriptor
        when (val transport = options.serverOptions.transport) {
            is AnalysisTransport.UnixDomainSocket -> {
                println("kast indexer listening on ${transport.socketPath}")
                println("descriptor: $descriptor")
            }
            AnalysisTransport.Stdio -> println("kast indexer serving JSON-RPC on stdio")
            is AnalysisTransport.Tcp -> println("kast indexer listening on ${transport.host}:${transport.port}")
        }
        runtime.await()
    }
}
