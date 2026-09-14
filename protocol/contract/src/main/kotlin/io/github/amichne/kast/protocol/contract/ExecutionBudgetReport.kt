package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A wire projection of admitted proof, never a second execution decision. */
@ConsistentCopyVisibility
@Serializable(with = ExecutionBudgetReportSerializer::class)
data class ExecutionBudgetReport
private constructor(
    @SerialName("max_elapsed_ms") val elapsed: ExecutionLimitDocument,
    @SerialName("max_work_units") val work: ExecutionLimitDocument,
    @SerialName("max_results") val results: ExecutionLimitDocument,
    @SerialName("max_returned_bytes") val returnedBytes: ExecutionLimitDocument,
) {
    companion object {
        internal fun admit(
            input: ExecutionBudgetReportBoundary
        ): Refinement<ExecutionBudgetReport, ExecutionBudgetReportFailure> =
            when {
                input.elapsed.clamping.any { it !in ELAPSED_CLAMPS } ->
                    Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_ELAPSED_CLAMP)
                input.work.clamping.any { it != ExecutionBudgetClampDocument.OPERATOR_CEILING } ->
                    Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_WORK_CLAMP)
                input.results.clamping.any { it !in OUTPUT_CLAMPS } ->
                    Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_RESULT_CLAMP)
                input.returnedBytes.clamping.any { it !in OUTPUT_CLAMPS } ->
                    Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_RETURNED_BYTE_CLAMP)
                input.results.exceedsResultRange() ->
                    Refinement.Rejected(ExecutionBudgetReportFailure.RESULT_AMOUNT_OUT_OF_RANGE)
                else ->
                    Refinement.Refined(
                        ExecutionBudgetReport(input.elapsed, input.work, input.results, input.returnedBytes)
                    )
            }

        fun from(grant: AdmittedExecutionBudget) =
            ExecutionBudgetReport(
                ExecutionLimitDocument.from(grant.elapsed) { it.value },
                ExecutionLimitDocument.from(grant.work) { it.value },
                ExecutionLimitDocument.from(grant.results) { it.value.toLong() },
                ExecutionLimitDocument.from(grant.returnedBytes) { it.value },
            )
    }
}

internal enum class ExecutionBudgetReportFailure {
    UNSUPPORTED_ELAPSED_CLAMP,
    UNSUPPORTED_WORK_CLAMP,
    UNSUPPORTED_RESULT_CLAMP,
    UNSUPPORTED_RETURNED_BYTE_CLAMP,
    RESULT_AMOUNT_OUT_OF_RANGE,
}

/** Individual positive limits remain boundary data until their dimension constraints are admitted. */
@Serializable
@SerialName("io.github.amichne.kast.protocol.contract.ExecutionBudgetReport")
internal data class ExecutionBudgetReportBoundary(
    @SerialName("max_elapsed_ms") val elapsed: ExecutionLimitDocument,
    @SerialName("max_work_units") val work: ExecutionLimitDocument,
    @SerialName("max_results") val results: ExecutionLimitDocument,
    @SerialName("max_returned_bytes") val returnedBytes: ExecutionLimitDocument,
)

internal object ExecutionBudgetReportSerializer : KSerializer<ExecutionBudgetReport> {
    private val delegate = ExecutionBudgetReportBoundary.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ExecutionBudgetReport) {
        delegate.serialize(
            encoder,
            ExecutionBudgetReportBoundary(value.elapsed, value.work, value.results, value.returnedBytes),
        )
    }

    override fun deserialize(decoder: Decoder): ExecutionBudgetReport =
        when (val admitted = ExecutionBudgetReport.admit(delegate.deserialize(decoder))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                throw SerializationException("ExecutionBudgetReport rejected: ${admitted.failure}")
        }
}

private fun ExecutionLimitDocument.exceedsResultRange(): Boolean =
    listOfNotNull(requested, configuredDefault, operatorCeiling, effective).any { it.value > Int.MAX_VALUE }

private val ELAPSED_CLAMPS =
    setOf(ExecutionBudgetClampDocument.OPERATOR_CEILING, ExecutionBudgetClampDocument.DEADLINE_REMAINING)
private val OUTPUT_CLAMPS =
    setOf(ExecutionBudgetClampDocument.OPERATOR_CEILING, ExecutionBudgetClampDocument.TRANSPORT_CAPACITY)
