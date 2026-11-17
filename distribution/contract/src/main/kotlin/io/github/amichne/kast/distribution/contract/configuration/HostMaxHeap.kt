package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement

sealed interface HostHeapPolicy {
    data object Inherit : HostHeapPolicy

    class Explicit private constructor(val heap: HostMaxHeap) : HostHeapPolicy {
        companion object {
            fun parseSetting(raw: String): Refinement<Explicit, HostMaxHeapFailure> =
                when (val parsed = HostMaxHeap.parseSetting(raw)) {
                    is Refinement.Refined -> Refinement.Refined(Explicit(parsed.value))
                    is Refinement.Rejected -> parsed
                }
        }
    }
}

/** The managed Host JVM's maximum heap. Control writes it as the only `-Xmx` option at each Host launch. */
class HostMaxHeap private constructor(val mebibytes: Int) {
    val jvmOption: String
        get() = "-Xmx${mebibytes}m"

    override fun equals(other: Any?): Boolean = other is HostMaxHeap && other.mebibytes == mebibytes

    override fun hashCode(): Int = mebibytes

    override fun toString(): String = "HostMaxHeap(mebibytes=$mebibytes)"

    companion object {
        const val MINIMUM_MEBIBYTES: Int = 1024

        /** Compressed object pointers stop applying above 32 GiB. */
        const val MAXIMUM_MEBIBYTES: Int = 32768

        const val DEFAULT_MEBIBYTES: Int = 1536

        val DEFAULT: HostMaxHeap = HostMaxHeap(DEFAULT_MEBIBYTES)

        private val vmOption = Regex("-Xmx([0-9]+)([kKmMgG]?)")

        internal fun parseSetting(raw: String): Refinement<HostMaxHeap, HostMaxHeapFailure> {
            val mebibytes =
                if (raw.isNotEmpty() && raw.all { it in '0'..'9' } && raw.length <= 5) raw.toInt()
                else return Refinement.Rejected(HostMaxHeapFailure.INVALID_SETTING)
            return if (mebibytes in MINIMUM_MEBIBYTES..MAXIMUM_MEBIBYTES) Refinement.Refined(HostMaxHeap(mebibytes))
            else Refinement.Rejected(HostMaxHeapFailure.OUT_OF_RANGE)
        }

        fun parseVmOption(raw: String): Refinement<HostMaxHeap, HostMaxHeapFailure> {
            val match = vmOption.matchEntire(raw) ?: return Refinement.Rejected(HostMaxHeapFailure.INVALID_VM_OPTION)
            val (digits, unit) = match.destructured
            val amount = digits.toLongOrNull() ?: return Refinement.Rejected(HostMaxHeapFailure.OUT_OF_RANGE)
            val mebibytes =
                when (unit.lowercase()) {
                    "g" -> if (amount <= Int.MAX_VALUE / 1024) amount * 1024 else 0
                    "m" -> amount
                    "k" -> if (amount % 1024 == 0L) amount / 1024 else 0
                    else -> if (amount % 1_048_576 == 0L) amount / 1_048_576 else 0
                }
            return if (mebibytes in 1..Int.MAX_VALUE.toLong()) Refinement.Refined(HostMaxHeap(mebibytes.toInt()))
            else Refinement.Rejected(HostMaxHeapFailure.OUT_OF_RANGE)
        }
    }
}

enum class HostMaxHeapFailure {
    INVALID_SETTING,
    INVALID_VM_OPTION,
    OUT_OF_RANGE,
}
