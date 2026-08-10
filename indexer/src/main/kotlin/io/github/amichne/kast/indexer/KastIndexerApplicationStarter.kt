package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationStarter
import kotlin.system.exitProcess

internal sealed interface IndexerProcessTermination {
    data object Stopped : IndexerProcessTermination
}

class KastIndexerApplicationStarter private constructor(
    private val runRuntime: (IndexerServerOptions) -> Unit,
    private val terminateProcess: (IndexerProcessTermination) -> Unit,
    @Suppress("UNUSED_PARAMETER") boundary: StarterConstructionBoundary,
) : ApplicationStarter {
    constructor() : this(
        runRuntime = KastIndexerRuntime::run,
        terminateProcess = { termination: IndexerProcessTermination ->
            when (termination) {
                IndexerProcessTermination.Stopped -> exitProcess(0)
            }
        },
        boundary = StarterConstructionBoundary,
    )

    internal constructor(
        runRuntime: (IndexerServerOptions) -> Unit,
        terminateProcess: (IndexerProcessTermination) -> Unit,
    ) : this(
        runRuntime = runRuntime,
        terminateProcess = terminateProcess,
        boundary = StarterConstructionBoundary,
    )

    override val isHeadless: Boolean = true
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    /**
     * Proof transition: `List<String> -> IndexerServerOptions -> IndexerProcessTermination.Stopped`.
     *
     * Establishes that raw IDEA starter arguments were parsed into launch options and that the
     * owned runtime reached its stopped state before process termination is requested. Raw process
     * exit-code extraction is permitted only in the injected operating-system termination boundary.
     */
    override fun main(args: List<String>) {
        runRuntime(IndexerServerOptions.parseStarterArgs(args))
        terminateProcess(IndexerProcessTermination.Stopped)
    }

    companion object {
        const val COMMAND_NAME: String = KAST_INDEXER_COMMAND_NAME
    }

    private data object StarterConstructionBoundary
}
