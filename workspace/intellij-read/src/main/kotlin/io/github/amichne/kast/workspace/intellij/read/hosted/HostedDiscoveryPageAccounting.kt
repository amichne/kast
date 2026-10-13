package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijDiscoveryPageMeasurements
import io.github.amichne.kast.workspace.intellij.read.IntellijDiscoveryPagePublication
import kotlinx.serialization.Serializable

internal class HostedDiscoveryPageOrdinal private constructor(val value: Int) {
    companion object {
        const val MAX_PAGES = 16

        fun admit(value: Int, limits: ReadLimits): Refinement<HostedDiscoveryPageOrdinal, HostedDiscoveryPageCapacity> =
            if (value in 1..minOf(MAX_PAGES, limits[ReadLimitParameter.DIAGNOSTIC_COUNT].value)) {
                Refinement.Refined(HostedDiscoveryPageOrdinal(value))
            } else Refinement.Rejected(HostedDiscoveryPageCapacity.EXHAUSTED)
    }
}

internal enum class HostedDiscoveryPageCapacity {
    EXHAUSTED
}

internal data class HostedDiscoveryPageRecord(
    val read: HostedReadTraceIdentity,
    val ordinal: HostedDiscoveryPageOrdinal,
    val measurements: IntellijDiscoveryPageMeasurements,
)

internal enum class HostedDiscoveryPagePublishOutcome {
    PUBLISHED,
    FAILED,
    UNAVAILABLE,
}

internal fun interface HostedDiscoveryPagePublisher {
    fun publish(record: HostedDiscoveryPageRecord): HostedDiscoveryPagePublishOutcome

    companion object {
        val Unavailable = HostedDiscoveryPagePublisher { HostedDiscoveryPagePublishOutcome.UNAVAILABLE }
    }
}

@Serializable
internal data class HostedDiscoveryPageCounts(
    val offered: Long = 0,
    val published: Long = 0,
    val suppressed: Long = 0,
    val failed: Long = 0,
    val unavailable: Long = 0,
    val qualification: HostedReadCallCountQualification = HostedReadCallCountQualification.EXACT,
)

/** Each read keeps fixed counters and publishes at most sixteen page records. */
internal class HostedDiscoveryPageAccounting(
    private val read: HostedReadTraceIdentity,
    private val limits: ReadLimits,
    private val publisher: HostedDiscoveryPagePublisher,
) {
    private var counts = HostedDiscoveryPageCounts()
    private var qualification = HostedReadCallCountQualification.EXACT
    private var slots = 0
    private var finished = false
    private val ceiling = limits[ReadLimitParameter.DIAGNOSTIC_COUNT].value.toLong()

    @Synchronized
    fun offer(measurements: IntellijDiscoveryPageMeasurements): IntellijDiscoveryPagePublication {
        if (finished) return IntellijDiscoveryPagePublication.INACTIVE
        counts = counts.copy(offered = increment(counts.offered))
        val ordinal =
            when (val admission = HostedDiscoveryPageOrdinal.admit(slots + 1, limits)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> {
                    counts = counts.copy(suppressed = increment(counts.suppressed))
                    return IntellijDiscoveryPagePublication.SUPPRESSED
                }
            }
        slots++
        return when (publisher.publish(HostedDiscoveryPageRecord(read, ordinal, measurements))) {
            HostedDiscoveryPagePublishOutcome.PUBLISHED -> {
                counts = counts.copy(published = increment(counts.published))
                IntellijDiscoveryPagePublication.PUBLISHED
            }
            HostedDiscoveryPagePublishOutcome.FAILED -> {
                counts = counts.copy(failed = increment(counts.failed))
                IntellijDiscoveryPagePublication.FAILED
            }
            HostedDiscoveryPagePublishOutcome.UNAVAILABLE -> {
                counts = counts.copy(unavailable = increment(counts.unavailable))
                IntellijDiscoveryPagePublication.UNOBSERVED
            }
        }
    }

    @Synchronized
    fun finish(): HostedDiscoveryPageCounts {
        finished = true
        return counts.copy(qualification = qualification)
    }

    private fun increment(value: Long): Long =
        if (value >= ceiling) {
            qualification = HostedReadCallCountQualification.SATURATED
            ceiling
        } else value + 1
}
