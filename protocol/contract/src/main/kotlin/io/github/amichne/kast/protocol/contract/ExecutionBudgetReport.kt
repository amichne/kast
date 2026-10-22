@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

/** A wire projection of admitted proof, never a second execution decision. */
@Serializable
@JsonClassDiscriminator("mode")
sealed interface ExecutionBudgetReport {
    val results: ExecutionLimitDocument
    val returnedBytes: ExecutionLimitDocument

    @ConsistentCopyVisibility
    @Serializable(with = BoundedExecutionBudgetReportSerializer::class)
    @SerialName("BOUNDED")
    data class Bounded
    private constructor(
        @SerialName("max_elapsed_ms") val elapsed: ExecutionLimitDocument,
        @SerialName("max_work_units") val work: ExecutionLimitDocument,
        @SerialName("max_results") override val results: ExecutionLimitDocument,
        @SerialName("max_returned_bytes") override val returnedBytes: ExecutionLimitDocument,
    ) : ExecutionBudgetReport {
        companion object {
            internal fun admit(
                input: BoundedExecutionBudgetReportInput
            ): Refinement<Bounded, ExecutionBudgetReportFailure> =
                when {
                    input.elapsed.clamping.any { it !in ELAPSED_CLAMPS } ->
                        Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_ELAPSED_CLAMP)
                    input.work.clamping.any { it != ExecutionBudgetClampDocument.OPERATOR_CEILING } ->
                        Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_WORK_CLAMP)
                    else ->
                        when (val output = admitOutput(input.results, input.returnedBytes)) {
                            is Refinement.Rejected -> output
                            is Refinement.Refined ->
                                Refinement.Refined(
                                    Bounded(input.elapsed, input.work, input.results, input.returnedBytes)
                                )
                        }
                }

            internal fun from(grant: AdmittedExecutionBudget.Bounded) =
                Bounded(
                    ExecutionLimitDocument.from(grant.elapsed) { it.value },
                    ExecutionLimitDocument.from(grant.work) { it.value },
                    ExecutionLimitDocument.from(grant.results) { it.value.toLong() },
                    ExecutionLimitDocument.from(grant.returnedBytes) { it.value },
                )
        }
    }

    @ConsistentCopyVisibility
    @Serializable(with = RunnerControlledExecutionBudgetReportSerializer::class)
    @SerialName("RUNNER_CONTROLLED")
    data class RunnerControlled
    private constructor(
        @SerialName("max_results") override val results: ExecutionLimitDocument,
        @SerialName("max_returned_bytes") override val returnedBytes: ExecutionLimitDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("max_elapsed_ms")
        @Serializable(with = ExecutionElapsedSerializer::class)
        val timeout: ElapsedTimeLimitMillis? = null,
    ) : ExecutionBudgetReport {
        companion object {
            internal fun admit(
                input: RunnerControlledExecutionBudgetReportInput
            ): Refinement<RunnerControlled, ExecutionBudgetReportFailure> =
                when (val output = admitOutput(input.results, input.returnedBytes)) {
                    is Refinement.Rejected -> output
                    is Refinement.Refined ->
                        Refinement.Refined(RunnerControlled(input.results, input.returnedBytes, input.timeout))
                }

            internal fun from(grant: AdmittedExecutionBudget.RunnerControlled) =
                RunnerControlled(
                    ExecutionLimitDocument.from(grant.results) { it.value.toLong() },
                    ExecutionLimitDocument.from(grant.returnedBytes) { it.value },
                    grant.timeout,
                )
        }
    }

    companion object {
        fun from(grant: AdmittedExecutionBudget): ExecutionBudgetReport =
            when (grant) {
                is AdmittedExecutionBudget.Bounded -> Bounded.from(grant)
                is AdmittedExecutionBudget.RunnerControlled -> RunnerControlled.from(grant)
            }

        fun from(grant: AdmittedExecutionBudget.Bounded): Bounded = Bounded.from(grant)
    }
}

internal enum class ExecutionBudgetReportFailure {
    UNSUPPORTED_ELAPSED_CLAMP,
    UNSUPPORTED_WORK_CLAMP,
    UNSUPPORTED_RESULT_CLAMP,
    UNSUPPORTED_RETURNED_BYTE_CLAMP,
    RESULT_AMOUNT_OUT_OF_RANGE,
}

@Serializable
@SerialName("BOUNDED")
internal data class BoundedExecutionBudgetReportInput(
    @SerialName("max_elapsed_ms") val elapsed: ExecutionLimitDocument,
    @SerialName("max_work_units") val work: ExecutionLimitDocument,
    @SerialName("max_results") val results: ExecutionLimitDocument,
    @SerialName("max_returned_bytes") val returnedBytes: ExecutionLimitDocument,
)

@Serializable
@SerialName("RUNNER_CONTROLLED")
internal data class RunnerControlledExecutionBudgetReportInput(
    @SerialName("max_results") val results: ExecutionLimitDocument,
    @SerialName("max_returned_bytes") val returnedBytes: ExecutionLimitDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("max_elapsed_ms")
    @Serializable(with = ExecutionElapsedSerializer::class)
    val timeout: ElapsedTimeLimitMillis? = null,
)

internal object BoundedExecutionBudgetReportSerializer : KSerializer<ExecutionBudgetReport.Bounded> {
    private val delegate = BoundedExecutionBudgetReportInput.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ExecutionBudgetReport.Bounded) =
        delegate.serialize(
            encoder,
            BoundedExecutionBudgetReportInput(value.elapsed, value.work, value.results, value.returnedBytes),
        )

    override fun deserialize(decoder: Decoder): ExecutionBudgetReport.Bounded =
        ExecutionBudgetReport.Bounded.admit(delegate.deserialize(decoder)).admittedReport()
}

internal object RunnerControlledExecutionBudgetReportSerializer : KSerializer<ExecutionBudgetReport.RunnerControlled> {
    private val delegate = RunnerControlledExecutionBudgetReportInput.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ExecutionBudgetReport.RunnerControlled) =
        delegate.serialize(
            encoder,
            RunnerControlledExecutionBudgetReportInput(value.results, value.returnedBytes, value.timeout),
        )

    override fun deserialize(decoder: Decoder): ExecutionBudgetReport.RunnerControlled =
        ExecutionBudgetReport.RunnerControlled.admit(delegate.deserialize(decoder)).admittedReport()
}

private fun admitOutput(
    results: ExecutionLimitDocument,
    bytes: ExecutionLimitDocument,
): Refinement<Unit, ExecutionBudgetReportFailure> =
    when {
        results.clamping.any { it !in OUTPUT_CLAMPS } ->
            Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_RESULT_CLAMP)
        bytes.clamping.any { it !in OUTPUT_CLAMPS } ->
            Refinement.Rejected(ExecutionBudgetReportFailure.UNSUPPORTED_RETURNED_BYTE_CLAMP)
        results.exceedsResultRange() -> Refinement.Rejected(ExecutionBudgetReportFailure.RESULT_AMOUNT_OUT_OF_RANGE)
        else -> Refinement.Refined(Unit)
    }

private fun <Report> Refinement<Report, ExecutionBudgetReportFailure>.admittedReport(): Report =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw SerializationException("ExecutionBudgetReport rejected: $failure")
    }

private fun ExecutionLimitDocument.exceedsResultRange(): Boolean =
    listOfNotNull(requested, configuredDefault, operatorCeiling, effective).any { it.value > Int.MAX_VALUE }

private val ELAPSED_CLAMPS =
    setOf(ExecutionBudgetClampDocument.OPERATOR_CEILING, ExecutionBudgetClampDocument.DEADLINE_REMAINING)
private val OUTPUT_CLAMPS =
    setOf(ExecutionBudgetClampDocument.OPERATOR_CEILING, ExecutionBudgetClampDocument.TRANSPORT_CAPACITY)
