package io.github.amichne.kast.kernel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A real elapsed deadline or caller-controlled lifetime. Unlimited is never a numeric sentinel. */
sealed interface ElapsedTimeAllowance {
    data class Bounded(val limit: ElapsedTimeLimitMillis) : ElapsedTimeAllowance

    data object Unlimited : ElapsedTimeAllowance

    val duration: Duration
        get() =
            when (this) {
                is Bounded -> limit.value.milliseconds
                Unlimited -> Duration.INFINITE
            }

    /** Nullable milliseconds exist only in the external lifecycle protocol: null means indefinite. */
    fun protocolMillis(): Long? =
        when (this) {
            is Bounded -> limit.value
            Unlimited -> null
        }

    companion object {
        fun admitMillis(raw: Long?): Refinement<ElapsedTimeAllowance, ElapsedTimeAllowanceFailure> =
            when (raw) {
                null -> Refinement.Refined(Unlimited)
                else ->
                    if (raw.milliseconds.isInfinite()) Refinement.Rejected(ElapsedTimeAllowanceFailure.INVALID_SETTING)
                    else
                        when (val limit = ElapsedTimeLimitMillis.parse(raw)) {
                            is Refinement.Refined -> Refinement.Refined(Bounded(limit.value))
                            is Refinement.Rejected -> Refinement.Rejected(ElapsedTimeAllowanceFailure.INVALID_SETTING)
                        }
            }

        fun parseSetting(raw: String): Refinement<ElapsedTimeAllowance, ElapsedTimeAllowanceFailure> =
            if (raw == "indefinite") Refinement.Refined(Unlimited)
            else
                when (val millis = raw.toLongOrNull()) {
                    null -> Refinement.Rejected(ElapsedTimeAllowanceFailure.INVALID_SETTING)
                    else ->
                        when (val admitted = admitMillis(millis)) {
                            is Refinement.Refined -> admitted
                            is Refinement.Rejected -> Refinement.Rejected(ElapsedTimeAllowanceFailure.INVALID_SETTING)
                        }
                }
    }
}

enum class ElapsedTimeAllowanceFailure {
    INVALID_SETTING
}
