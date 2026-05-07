package io.amichne.konditional.api

import io.amichne.konditional.context.RampUp

@ConsistentCopyVisibility
data class BucketInfo internal constructor(
    val featureKey: String,
    val salt: String,
    val bucket: Int,
    val rollout: RampUp,
    val thresholdBasisPoints: Int,
    val inRollout: Boolean,
)
