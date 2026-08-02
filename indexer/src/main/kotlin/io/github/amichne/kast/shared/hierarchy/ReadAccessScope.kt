package io.github.amichne.kast.shared.hierarchy

/**
 * Abstraction for acquiring a read lock around PSI access.
 *
 * The indexer can delegate to a platform read action or use the identity
 * implementation when the caller already owns the session read lock.
 */
interface ReadAccessScope {
    fun <T> run(action: () -> T): T

    companion object {
        /** Identity implementation — executes the action directly without acquiring any lock. */
        val IDENTITY: ReadAccessScope = object : ReadAccessScope {
            override fun <T> run(action: () -> T): T = action()
        }
    }
}
