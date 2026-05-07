@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.registry

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.ops.Metrics
import io.amichne.konditional.core.ops.RegistryHooks
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [NamespaceRegistry] implementation.
 *
 * ## Atomicity
 *
 * Reads and writes are linearizable at the [NamespaceSnapshot] boundary.
 * A single `AtomicReference<NamespaceSnapshot>` holds the current snapshot per namespace.
 * All writes (load, rollback, disableAll, enableAll) go through a `writeLock` to keep the
 * `current` and `historyRef` consistent with each other. Reads never acquire a lock.
 *
 * Readers will observe either the complete previous snapshot or the complete new snapshot —
 * never a partially-applied configuration.
 *
 * Intended for:
 * - default runtime registry for [Namespace]
 * - tests requiring isolated registries
 */
class InMemoryNamespaceRegistry(
    override val namespaceId: String,
    hooks: RegistryHooks = RegistryHooks.None,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) : NamespaceRegistry {
    private val current = AtomicReference(NamespaceSnapshot.empty)
    private val hooksRef = AtomicReference(hooks)
    private val historyRef = AtomicReference<List<NamespaceSnapshot>>(emptyList())
    private val writeLock = Any()

    override fun load(config: Configuration) {
        replaceSnapshot { snapshot ->
            snapshot.copy(configuration = config)
        }

        hooksRef.get().metrics.recordConfigLoad(
            Metrics.ConfigLoadMetric.of(
                namespaceId = namespaceId,
                featureCount = config.flags.size,
                version = config.metadata.version,
            ),
        )
    }

    override val configuration: Configuration
        get() = current.get().configuration

    /**
     * The current [NamespaceSnapshot] held by this registry.
     *
     * Callers observing this value are guaranteed to see either a complete previous snapshot
     * or a complete new snapshot — never partial state.
     */
    val currentSnapshot: NamespaceSnapshot
        get() = current.get()

    override val hooks: RegistryHooks
        get() = hooksRef.get()

    override fun setHooks(hooks: RegistryHooks) {
        hooksRef.set(hooks)
    }

    override val isAllDisabled: Boolean
        get() = current.get().allDisabled

    override fun disableAll() {
        replaceSnapshot { snapshot ->
            if (snapshot.allDisabled) {
                snapshot
            } else {
                snapshot.copy(allDisabled = true)
            }
        }
    }

    override fun enableAll() {
        replaceSnapshot { snapshot ->
            if (snapshot.allDisabled) {
                snapshot.copy(allDisabled = false)
            } else {
                snapshot
            }
        }
    }

    override val history: List<NamespaceSnapshot>
        get() = historyRef.get()

    override fun rollback(steps: Int): Boolean {
        require(steps >= 1) { "steps must be >= 1" }

        val restored =
            synchronized(writeLock) {
                val history = historyRef.get()
                if (history.size < steps) return false

                val targetIndex = history.size - steps
                val target = history[targetIndex]
                val newHistory = history.take(targetIndex)

                current.set(target)
                historyRef.set(newHistory)
                target
            }

        hooksRef.get().metrics.recordConfigRollback(
            Metrics.ConfigRollbackMetric.of(
                namespaceId = namespaceId,
                steps = steps,
                success = true,
                version = restored.version,
            ),
        )

        return true
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT: Int = 10
    }

    private fun replaceSnapshot(
        transform: (NamespaceSnapshot) -> NamespaceSnapshot,
    ): NamespaceSnapshot =
        synchronized(writeLock) {
            val previous = current.get()
            val next = transform(previous)
            if (next == previous) {
                next
            } else {
                current.set(next)
                historyRef.set((historyRef.get() + previous).takeLast(historyLimit))
                next
            }
        }
}
