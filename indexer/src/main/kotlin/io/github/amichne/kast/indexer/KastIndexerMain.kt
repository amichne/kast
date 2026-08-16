package io.github.amichne.kast.indexer

import kotlin.system.exitProcess

/** Installed process entrypoint; exact-root runtime bootstrap is admitted before serving. */
fun main() {
    System.err.println("kast-indexer: runtime bootstrap is unavailable")
    exitProcess(70)
}
