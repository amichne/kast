package io.github.amichne.kast.cli.installation

import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class LegacyQuitOutcome {
    REQUESTED,
    REJECTED,
    PROCESS_IDENTITY_CHANGED,
    HELPER_START_FAILED,
    HELPER_TIMED_OUT,
    HELPER_CAPTURE_FAILED,
    HELPER_OUTPUT_LIMIT_EXCEEDED,
    HELPER_INTERRUPTED,
    HELPER_EXIT_FAILED,
    HELPER_RESPONSE_INVALID,
}

@Serializable
internal enum class LegacyQuitStage {
    NORMAL_QUIT
}

@Serializable
internal sealed interface LegacyQuitObservation {
    @Serializable
    @SerialName("RESULT")
    data class Result(val outcome: LegacyQuitOutcome, val stage: LegacyQuitStage = LegacyQuitStage.NORMAL_QUIT) :
        LegacyQuitObservation

    @Serializable
    @SerialName("HELPER_EXIT_FAILED")
    data class HelperExited(val exitCode: Int, val stage: LegacyQuitStage = LegacyQuitStage.NORMAL_QUIT) :
        LegacyQuitObservation
}

internal fun classifyLegacyQuit(response: LegacyHelperRead): LegacyQuitOutcome =
    when (response) {
        is LegacyHelperRead.Read ->
            try {
                when (response.bytes.decodeToString(throwOnInvalidSequence = true).trim()) {
                    "requested" -> LegacyQuitOutcome.REQUESTED
                    "rejected" -> LegacyQuitOutcome.REJECTED
                    else -> LegacyQuitOutcome.HELPER_RESPONSE_INVALID
                }
            } catch (_: CharacterCodingException) {
                LegacyQuitOutcome.HELPER_RESPONSE_INVALID
            }
        is LegacyHelperRead.Exited -> LegacyQuitOutcome.HELPER_EXIT_FAILED
        is LegacyHelperRead.Unavailable ->
            when (response.reason) {
                LegacyHelperFailure.START_FAILED -> LegacyQuitOutcome.HELPER_START_FAILED
                LegacyHelperFailure.TIMED_OUT -> LegacyQuitOutcome.HELPER_TIMED_OUT
                LegacyHelperFailure.CAPTURE_FAILED -> LegacyQuitOutcome.HELPER_CAPTURE_FAILED
                LegacyHelperFailure.OUTPUT_LIMIT_EXCEEDED -> LegacyQuitOutcome.HELPER_OUTPUT_LIMIT_EXCEEDED
                LegacyHelperFailure.INTERRUPTED -> LegacyQuitOutcome.HELPER_INTERRUPTED
            }
    }

internal fun reportLegacyQuit(observation: LegacyQuitObservation) {
    System.err.println(encodeLegacyQuit(observation))
}

internal fun encodeLegacyQuit(observation: LegacyQuitObservation): String = legacyQuitJson.encodeToString(observation)

private val legacyQuitJson = Json { encodeDefaults = true }
