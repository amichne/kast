package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.ExactRevalidationReferences
import io.github.amichne.kast.symbol.contract.ExactRevalidationLocator
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection

/** Separate project lifetime, detached-only retention. Strict reference tables never consult it. */
@Service(Service.Level.PROJECT)
class HostedExactRevalidationStore : Disposable {
    private val records = ExactRevalidationRecords()

    fun references(): ExactRevalidationReferences = records

    fun retain(
        token: ProtocolText,
        canonical: ProtocolText,
        locator: ExactRevalidationLocator,
    ): Refinement<Unit, ExactRevalidationRejection> = records.retain(token, canonical, locator)

    override fun dispose() = records.dispose()
}

/**
 * Fixed 256 entries / 4 MiB charge / 5 minutes. Replays do not renew retained entries. When capacity is needed, older
 * epochs are evicted before current-epoch locators; evicted tokens become explicitly unretained.
 */
internal class ExactRevalidationRecords(
    private val now: () -> Long = System::nanoTime,
    private val maxEntries: Int = 256,
    private val maxBytes: Long = MAX_REVALIDATION_RETAINED_BYTES,
    private val maxAgeNanos: Long = 300_000_000_000L,
) : ExactRevalidationReferences {
    private data class Record(val locator: ExactRevalidationLocator, val created: Long, val bytes: Long)

    private val entries = mutableMapOf<ProtocolText, Record>()
    private var retainedBytes = 0L
    private var disposed = false

    @Synchronized
    fun retain(
        token: ProtocolText,
        canonical: ProtocolText,
        locator: ExactRevalidationLocator,
    ): Refinement<Unit, ExactRevalidationRejection> {
        if (disposed) return rejected(ExactRevalidationRejection.RETIRED)
        val tick = now()
        val existing = entries[token]
        if (existing != null)
            return if (expired(existing, tick)) rejected(ExactRevalidationRejection.EXPIRED)
            else Refinement.Refined(Unit)
        // Four bytes per UTF-16 code unit plus fixed object/map overhead conservatively charges duplicated detached
        // strings.
        val owner = locator.owner
        val bytes =
            1024L +
                4L *
                    (canonical.value.length +
                        token.value.length +
                        owner.module.value.length +
                        owner.project.buildRoot.value.length +
                        owner.project.projectPath.value.length +
                        owner.sourceSet.value.length +
                        owner.sourceRoot.value.length)
        if (bytes > maxBytes) return rejected(ExactRevalidationRejection.CAPACITY)
        evictHistorical(locator, bytes)
        if (entries.size >= maxEntries || bytes > maxBytes - retainedBytes)
            return rejected(ExactRevalidationRejection.CAPACITY)
        entries[token] = Record(locator, tick, bytes)
        retainedBytes += bytes
        return Refinement.Refined(Unit)
    }

    private fun evictHistorical(locator: ExactRevalidationLocator, bytes: Long) {
        val historical =
            entries.entries
                .filter { (_, record) ->
                    record.locator.host == locator.host && record.locator.epoch.value < locator.epoch.value
                }
                .sortedBy { it.value.created }
        for ((key, record) in historical) {
            if (entries.size < maxEntries && bytes <= maxBytes - retainedBytes) break
            entries.remove(key)
            retainedBytes -= record.bytes
        }
    }

    @Synchronized
    override fun locate(token: ProtocolText): Refinement<ExactRevalidationLocator, ExactRevalidationRejection> {
        if (disposed) return rejected(ExactRevalidationRejection.RETIRED)
        if (!token.value.startsWith("exact:")) return rejected(ExactRevalidationRejection.WRONG_KIND)
        val record = entries[token] ?: return rejected(ExactRevalidationRejection.UNRETAINED)
        if (expired(record, now())) return rejected(ExactRevalidationRejection.EXPIRED)
        return Refinement.Refined(record.locator)
    }

    @Synchronized
    fun dispose() {
        entries.clear()
        retainedBytes = 0
        disposed = true
    }

    private fun expired(record: Record, tick: Long): Boolean =
        tick - record.created >= maxAgeNanos || tick < record.created
}

private fun rejected(reason: ExactRevalidationRejection) = Refinement.Rejected(reason)

private const val MAX_REVALIDATION_RETAINED_BYTES = 4L * 1024 * 1024
