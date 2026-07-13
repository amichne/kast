package io.github.amichne.kast.shared.hierarchy

/**
 * Abstraction for acquiring a read lock around PSI access.
 *
 * - IDEA plugin backend: delegates to `ApplicationManager.getApplication().runReadAction`.
 * - Headless backend: identity (session-level read lock is already held).
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
