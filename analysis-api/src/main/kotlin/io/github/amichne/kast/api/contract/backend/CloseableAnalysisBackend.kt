package io.github.amichne.kast.api.contract

/**
 * A backend whose lifetime can be transferred to a server runtime.
 *
 * The caller retains ownership when server startup fails. A successfully
 * started server becomes the sole close owner.
 */
interface CloseableAnalysisBackend : AnalysisBackend, AutoCloseable {
    override fun close()
}
