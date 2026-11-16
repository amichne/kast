package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement

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

        fun parseSetting(raw: String): Refinement<HostMaxHeap, HostMaxHeapFailure> {
            val mebibytes =
                if (raw.isNotEmpty() && raw.all { it in '0'..'9' } && raw.length <= 5) raw.toInt()
                else return Refinement.Rejected(HostMaxHeapFailure.INVALID_SETTING)
            return if (mebibytes in MINIMUM_MEBIBYTES..MAXIMUM_MEBIBYTES) Refinement.Refined(HostMaxHeap(mebibytes))
            else Refinement.Rejected(HostMaxHeapFailure.OUT_OF_RANGE)
        }
    }
}

enum class HostMaxHeapFailure {
    INVALID_SETTING,
    OUT_OF_RANGE,
}
