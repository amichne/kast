package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationStarter

class KastIndexerApplicationStarter(
    private val runRuntime: (IndexerServerOptions) -> Unit = KastIndexerRuntime::run,
) : ApplicationStarter {
    override val isHeadless: Boolean = true
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    override fun main(args: List<String>) {
        runRuntime(IndexerServerOptions.parseStarterArgs(args))
    }

    companion object {
        const val COMMAND_NAME: String = KAST_INDEXER_COMMAND_NAME
    }
}
