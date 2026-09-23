package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
internal enum class NativePlanStage {
    RESPONSE,
    IDENTITY,
}

@Serializable
internal enum class NativePlanStageOutcome {
    COMPLETE,
    REJECTED,
}

@Serializable
internal data class NativePlanStageObservation(
    val stage: NativePlanStage,
    val outcome: NativePlanStageOutcome,
    val event: String = "kast_native_plan_stage",
) {
    fun encode(): String = nativePlanStageJson.encodeToString(serializer(), this)
}

private val nativePlanStageJson = Json { encodeDefaults = true }

/** Bounded response-stage evidence; source text and tool payloads never enter the observation. */
internal fun observeNativePlanStage(stage: NativePlanStage, accepted: Boolean) {
    println(
        NativePlanStageObservation(
                stage,
                if (accepted) NativePlanStageOutcome.COMPLETE else NativePlanStageOutcome.REJECTED,
            )
            .encode()
    )
    System.out.flush()
}

@Serializable
internal enum class NativePlanInvariant {
    UNCHANGED,
    SOURCE_CHANGED,
    AUTHORITY_CHANGED,
    SOURCE_AND_AUTHORITY_CHANGED,
}

internal fun observeNativePlan(
    original: ByteArray,
    current: ByteArray,
    plannedLive: JsonObject,
    currentLive: JsonObject,
): NativePlanInvariant =
    if (original.contentEquals(current)) {
        if (plannedLive == currentLive) NativePlanInvariant.UNCHANGED else NativePlanInvariant.AUTHORITY_CHANGED
    } else {
        if (plannedLive == currentLive) NativePlanInvariant.SOURCE_CHANGED
        else NativePlanInvariant.SOURCE_AND_AUTHORITY_CHANGED
    }

internal fun NativeChangeEvidence.verifyPlan(observed: NativePlanInvariant) {
    record(
        "plan-has-no-source-effect",
        if (observed == NativePlanInvariant.UNCHANGED) NativeCaseOutcome.PASSED else NativeCaseOutcome.REJECTED,
        Json.encodeToJsonElement(NativePlanObservation.serializer(), NativePlanObservation(observed)).jsonObject,
    )
    val failure =
        when (observed) {
            NativePlanInvariant.UNCHANGED -> return
            NativePlanInvariant.SOURCE_CHANGED -> NativeFailure.SOURCE_CHANGED
            NativePlanInvariant.AUTHORITY_CHANGED -> NativeFailure.AUTHORITY_CHANGED
            NativePlanInvariant.SOURCE_AND_AUTHORITY_CHANGED -> NativeFailure.SOURCE_AND_AUTHORITY_CHANGED
        }
    throw NativeRejected(failure)
}

@Serializable private data class NativePlanObservation(val invariant: NativePlanInvariant)
