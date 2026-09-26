@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Derived advice never grants a retry, continuation, or mutation capability. */
@Serializable
@JsonClassDiscriminator("action")
sealed interface ReadRecoveryGuidance {
    @Serializable
    @SerialName("resume")
    class Resume private constructor() : ReadRecoveryGuidance {
        companion object {
            val Required = Resume()
        }

        @EncodeDefault
        val instruction: String =
            "Pass the returned continuation with the same target, scope and traversal strategy. Retain this partial page."
    }

    @Serializable
    @SerialName("increase_budget")
    data class IncreaseBudget(val field: ReadBudgetField, val allowance: ExecutionLimitDocument) :
        ReadRecoveryGuidance {
        @EncodeDefault
        val instruction: String =
            "Start a new read with a larger value for this execution_budget field, no higher than operatorCeiling. Completion is not guaranteed."
    }

    @Serializable
    @SerialName("narrow_scope")
    data class NarrowScope(val field: ReadBudgetField) : ReadRecoveryGuidance {
        @EncodeDefault
        val instruction: String =
            "Start a new read with a smaller file, directory or source-set scope. Increasing the caller budget alone is not proven to help."
    }

    @Serializable
    @SerialName("change_depth")
    class ChangeDepth private constructor() : ReadRecoveryGuidance {
        companion object {
            val Required = ChangeDepth()
        }

        @EncodeDefault
        val instruction: String =
            "This traversal is incomplete at maximumDepth. Start a new traversal with greater depth within the configured ceiling, or use a more focused start symbol."
    }

    @Serializable
    @SerialName("reduce_frontier")
    class ReduceFrontier private constructor() : ReadRecoveryGuidance {
        companion object {
            val Required = ReduceFrontier()
        }

        @EncodeDefault
        val instruction: String =
            "Start a new traversal from a more focused symbol or narrower scope; the frontier capacity was exhausted."
    }

    @Serializable
    @SerialName("wait_for_workspace")
    class WaitForWorkspace private constructor() : ReadRecoveryGuidance {
        companion object {
            val Required = WaitForWorkspace()
        }

        @EncodeDefault
        val instruction: String = "Check IDE readiness and wait for indexing to finish before starting a new read."
    }

    @Serializable
    @SerialName("inspect_omissions")
    class InspectOmissions private constructor() : ReadRecoveryGuidance {
        companion object {
            val Required = InspectOmissions()
        }

        @EncodeDefault
        val instruction: String =
            "Inspect the returned omission reasons. More budget cannot establish unsupported or unresolved compiler evidence."
    }

    @Serializable
    @SerialName("report_failure")
    class ReportFailure private constructor() : ReadRecoveryGuidance {
        companion object {
            val Required = ReportFailure()
        }

        @EncodeDefault
        val instruction: String =
            "Report the finite provider failure or lack of progress with the operation and limitations. Do not repeat the same request indefinitely."
    }
}

@Serializable
enum class ReadBudgetField {
    @SerialName("max_elapsed_ms") ELAPSED,
    @SerialName("max_work_units") WORK,
    @SerialName("max_results") RESULTS,
    @SerialName("max_returned_bytes") BYTES,
}

fun TraversalRunQualification.recoveryGuidance(report: ExecutionBudgetReport?): List<ReadRecoveryGuidance> =
    when (this) {
        is TraversalRunQualification.Resumable -> listOf(ReadRecoveryGuidance.Resume.Required)
        is TraversalRunQualification.TerminalIncomplete ->
            (limitations.flatMap { cause ->
                    when (cause) {
                        TraversalLimitationDocument.RECORD_LIMIT_REACHED ->
                            listOf(ReadBudgetField.RESULTS.guidance(report))
                        TraversalLimitationDocument.BYTE_LIMIT_REACHED -> listOf(ReadBudgetField.BYTES.guidance(report))
                        TraversalLimitationDocument.WORK_LIMIT_REACHED -> listOf(ReadBudgetField.WORK.guidance(report))
                        TraversalLimitationDocument.TIME_LIMIT_REACHED ->
                            listOf(ReadBudgetField.ELAPSED.guidance(report))
                        TraversalLimitationDocument.DEPTH_LIMIT_REACHED ->
                            listOf(ReadRecoveryGuidance.ChangeDepth.Required)
                        TraversalLimitationDocument.FRONTIER_LIMIT_REACHED ->
                            listOf(ReadRecoveryGuidance.ReduceFrontier.Required)
                        TraversalLimitationDocument.ONE_HOP_INCOMPLETE ->
                            relationLimitations.map { it.guidance(report) }
                        TraversalLimitationDocument.NO_PROGRESS -> listOf(ReadRecoveryGuidance.ReportFailure.Required)
                    }
                })
                .distinct()
    }

private fun RelationLimitationDocument.guidance(report: ExecutionBudgetReport?): ReadRecoveryGuidance =
    when (this) {
        RelationLimitationDocument.RESULT_LIMIT_REACHED -> ReadBudgetField.RESULTS.guidance(report)
        RelationLimitationDocument.BYTE_LIMIT_REACHED -> ReadBudgetField.BYTES.guidance(report)
        RelationLimitationDocument.WORK_LIMIT_REACHED -> ReadBudgetField.WORK.guidance(report)
        RelationLimitationDocument.TIME_LIMIT_REACHED -> ReadBudgetField.ELAPSED.guidance(report)
        RelationLimitationDocument.DUMB_MODE_TRANSITION -> ReadRecoveryGuidance.WaitForWorkspace.Required
        RelationLimitationDocument.UNRESOLVED_TARGET,
        RelationLimitationDocument.UNSUPPORTED_ITEM,
        RelationLimitationDocument.PROVIDER_INCOMPLETE -> ReadRecoveryGuidance.InspectOmissions.Required
        RelationLimitationDocument.PROVIDER_FAILURE,
        RelationLimitationDocument.PROVIDER_STALLED -> ReadRecoveryGuidance.ReportFailure.Required
    }

private fun ReadBudgetField.guidance(report: ExecutionBudgetReport?): ReadRecoveryGuidance {
    val allowance =
        when (this) {
            ReadBudgetField.ELAPSED -> report?.elapsed
            ReadBudgetField.WORK -> report?.work
            ReadBudgetField.RESULTS -> report?.results
            ReadBudgetField.BYTES -> report?.returnedBytes
        }
    return if (
        allowance != null && allowance.clamping.isEmpty() && allowance.effective.value < allowance.operatorCeiling.value
    )
        ReadRecoveryGuidance.IncreaseBudget(this, allowance)
    else ReadRecoveryGuidance.NarrowScope(this)
}
