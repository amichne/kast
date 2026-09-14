package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.AdmittedExecutionLimit
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ExecutionBudgetClamp
import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A positive amount at the report boundary; the containing field retains its budget dimension. */
@JvmInline
@Serializable(with = ExecutionReportAmountSerializer::class)
value class ExecutionReportAmount private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<ExecutionReportAmount, PositiveLimitFailure> =
            if (raw > 0L) Refinement.Refined(ExecutionReportAmount(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

internal object ExecutionReportAmountSerializer :
    RefiningLongSerializer<ExecutionReportAmount>("ExecutionReportAmount", 1) {
    override fun raw(value: ExecutionReportAmount) = value.value

    override fun refine(raw: Long) = ExecutionReportAmount.parse(raw)
}

internal enum class ExecutionLimitDocumentFailure {
    SELECTION_MISMATCH,
    NON_CANONICAL_CLAMPING,
    EFFECTIVE_LIMIT_MISMATCH,
    CLAMPING_MISMATCH,
}

@ConsistentCopyVisibility
@Serializable(with = ExecutionLimitDocumentSerializer::class)
data class ExecutionLimitDocument
private constructor(
    val selection: ExecutionBudgetSelectionDocument,
    val requested: ExecutionReportAmount?,
    val configuredDefault: ExecutionReportAmount,
    val operatorCeiling: ExecutionReportAmount,
    val effective: ExecutionReportAmount,
    val clamping: List<ExecutionBudgetClampDocument>,
) {
    companion object {
        internal fun <Value> from(limit: AdmittedExecutionLimit<Value>, raw: (Value) -> Long): ExecutionLimitDocument =
            ExecutionLimitDocument(
                when (limit.requested) {
                    ExecutionAllowance.Default -> ExecutionBudgetSelectionDocument.CONFIGURED_DEFAULT
                    is ExecutionAllowance.Requested -> ExecutionBudgetSelectionDocument.CALLER
                },
                when (val requested = limit.requested) {
                    ExecutionAllowance.Default -> null
                    is ExecutionAllowance.Requested -> ExecutionReportAmount.parse(raw(requested.value)).proven()
                },
                ExecutionReportAmount.parse(raw(limit.configuredDefault)).proven(),
                ExecutionReportAmount.parse(raw(limit.operatorCeiling)).proven(),
                ExecutionReportAmount.parse(raw(limit.effective)).proven(),
                limit.clamping
                    .map { cause ->
                        when (cause) {
                            ExecutionBudgetClamp.OPERATOR_CEILING -> ExecutionBudgetClampDocument.OPERATOR_CEILING
                            ExecutionBudgetClamp.TRANSPORT_CAPACITY -> ExecutionBudgetClampDocument.TRANSPORT_CAPACITY
                            ExecutionBudgetClamp.DEADLINE_REMAINING -> ExecutionBudgetClampDocument.DEADLINE_REMAINING
                        }
                    }
                    .sortedBy { it.ordinal },
            )

        internal fun admit(
            input: ExecutionLimitBoundary
        ): Refinement<ExecutionLimitDocument, ExecutionLimitDocumentFailure> =
            when (val validated = input.validate()) {
                is Refinement.Rejected -> validated
                is Refinement.Refined ->
                    Refinement.Refined(
                        ExecutionLimitDocument(
                            input.selection,
                            input.requested,
                            input.configuredDefault,
                            input.operatorCeiling,
                            input.effective,
                            input.clamping.toList(),
                        )
                    )
            }
    }
}

/** Fixed wire shape, refined before it becomes report evidence. */
@Serializable
@SerialName("io.github.amichne.kast.protocol.contract.ExecutionLimitDocument")
internal data class ExecutionLimitBoundary(
    val selection: ExecutionBudgetSelectionDocument,
    val requested: ExecutionReportAmount?,
    val configuredDefault: ExecutionReportAmount,
    val operatorCeiling: ExecutionReportAmount,
    val effective: ExecutionReportAmount,
    val clamping: List<ExecutionBudgetClampDocument>,
) {
    fun validate(): Refinement<ExecutionReportAmount, ExecutionLimitDocumentFailure> {
        val selected =
            when (selection) {
                ExecutionBudgetSelectionDocument.CONFIGURED_DEFAULT -> {
                    if (requested != null) return Refinement.Rejected(ExecutionLimitDocumentFailure.SELECTION_MISMATCH)
                    configuredDefault
                }
                ExecutionBudgetSelectionDocument.CALLER ->
                    requested ?: return Refinement.Rejected(ExecutionLimitDocumentFailure.SELECTION_MISMATCH)
            }
        return when {
            clamping != clamping.distinct().sortedBy { it.ordinal } ->
                Refinement.Rejected(ExecutionLimitDocumentFailure.NON_CANONICAL_CLAMPING)
            effective.value > minOf(selected.value, operatorCeiling.value) ->
                Refinement.Rejected(ExecutionLimitDocumentFailure.EFFECTIVE_LIMIT_MISMATCH)
            !clampingMatches(selected) -> Refinement.Rejected(ExecutionLimitDocumentFailure.CLAMPING_MISMATCH)
            else -> Refinement.Refined(selected)
        }
    }

    private fun clampingMatches(selected: ExecutionReportAmount): Boolean =
        (clamping.isEmpty() == (selected == effective)) &&
            ((ExecutionBudgetClampDocument.OPERATOR_CEILING in clamping) == (selected.value > operatorCeiling.value)) &&
            (clamping.any { it != ExecutionBudgetClampDocument.OPERATOR_CEILING } ||
                effective.value == minOf(selected.value, operatorCeiling.value))
}

internal object ExecutionLimitDocumentSerializer : KSerializer<ExecutionLimitDocument> {
    private val delegate = ExecutionLimitBoundary.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ExecutionLimitDocument) =
        delegate.serialize(
            encoder,
            ExecutionLimitBoundary(
                value.selection,
                value.requested,
                value.configuredDefault,
                value.operatorCeiling,
                value.effective,
                value.clamping,
            ),
        )

    override fun deserialize(decoder: Decoder): ExecutionLimitDocument =
        when (val admitted = ExecutionLimitDocument.admit(delegate.deserialize(decoder))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                throw SerializationException("Invalid execution limit evidence: ${admitted.failure.name}")
        }
}

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted budget lost its positive amount")
    }
