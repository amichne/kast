package io.amichne.konditional.core.evaluation

import io.amichne.konditional.context.RampUp
import io.amichne.konditional.core.id.HexId
import java.security.MessageDigest
import kotlin.math.roundToInt

@PublishedApi
internal object Bucketing {
    private const val BUCKET_SPACE: Int = 10_000
    private const val MISSING_STABLE_ID_BUCKET: Int = BUCKET_SPACE - 1

    private val threadLocalDigest = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }

    /**
     * Computes a stable bucket assignment in the range [0, 10_000).
     *
     * The bucketing input is deterministic for a given `(salt, flagKey, stableId)` triple.
     */
    fun stableBucket(
        salt: String,
        flagKey: String,
        stableId: HexId,
    ): Int {
        val digest = threadLocalDigest.get()
        return with(digest.digest("$salt:$flagKey:${stableId.id}".toByteArray(Charsets.UTF_8))) {
            (
                (
                    get(0).toInt() and 0xFF shl 24 or
                        (get(1).toInt() and 0xFF shl 16) or
                        (get(2).toInt() and 0xFF shl 8) or
                        (get(3).toInt() and 0xFF)
                    ).toLong() and 0xFFFF_FFFFL
                ).mod(BUCKET_SPACE.toLong()).toInt()
        }
    }

    /**
     * Converts a rampUp percentage (0.0-100.0) into basis points (0-10_000).
     */
    fun rampUpThresholdBasisPoints(rollout: RampUp): Int = (rollout.value * 100.0).roundToInt()

    /**
     * Default bucket for contexts without a stable ID.
     *
     * This keeps the evaluation deterministic while avoiding exceptions.
     */
    fun missingStableIdBucket(): Int = MISSING_STABLE_ID_BUCKET

    fun isInRampUp(
        rampUp: RampUp,
        bucket: Int,
    ): Boolean =
        when {
            rampUp <= 0.0 -> false
            rampUp >= 100.0 -> true
            else -> bucket < rampUpThresholdBasisPoints(rampUp)
        }
}
