package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement

/** Whether a managed Host stops itself after a period without admitted work. Control and Host share one parser. */
sealed interface HostIdleShutdown {
    data object Disabled : HostIdleShutdown

    class After private constructor(val minutes: Int) : HostIdleShutdown {
        val millis: Long
            get() = minutes * MILLIS_PER_MINUTE

        override fun equals(other: Any?): Boolean = other is After && other.minutes == minutes

        override fun hashCode(): Int = minutes

        override fun toString(): String = "After(minutes=$minutes)"

        internal companion object {
            fun admit(minutes: Int): After = After(minutes)
        }
    }

    companion object {
        const val MINIMUM_MINUTES: Int = 5
        const val MAXIMUM_MINUTES: Int = 1440
        const val DISABLED_SETTING: String = "off"

        /** The launch input carries only an admitted limit; absence means [Disabled]. */
        const val JVM_PROPERTY: String = "kast.host.idle_shutdown_minutes"

        private const val MILLIS_PER_MINUTE = 60_000L

        fun parseSetting(raw: String): Refinement<HostIdleShutdown, HostIdleShutdownFailure> =
            if (raw == DISABLED_SETTING) Refinement.Refined(Disabled) else parseMinutes(raw)

        /** The Host admits only a limit Control wrote; `off` or malformed launch input is rejected. */
        fun parseJvmProperty(raw: String?): Refinement<HostIdleShutdown, HostIdleShutdownFailure> =
            if (raw == null) Refinement.Refined(Disabled) else parseMinutes(raw)

        fun jvmOptions(policy: HostIdleShutdown): List<String> =
            when (policy) {
                Disabled -> emptyList()
                is After -> listOf("-D$JVM_PROPERTY=${policy.minutes}")
            }

        private fun parseMinutes(raw: String): Refinement<HostIdleShutdown, HostIdleShutdownFailure> {
            val minutes =
                if (raw.isNotEmpty() && raw.all { it in '0'..'9' } && raw.length <= 4) raw.toInt()
                else return Refinement.Rejected(HostIdleShutdownFailure.INVALID_SETTING)
            return if (minutes in MINIMUM_MINUTES..MAXIMUM_MINUTES) Refinement.Refined(After.admit(minutes))
            else Refinement.Rejected(HostIdleShutdownFailure.OUT_OF_RANGE)
        }
    }
}

enum class HostIdleShutdownFailure {
    INVALID_SETTING,
    OUT_OF_RANGE,
}
