package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.AdmittedExecutionLimit
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ExecutionBudgetClamp
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional boundary controls; all supplied numbers are refined during deserialization. */
@Serializable
data class ExecutionBudgetDocument(
    @SerialName("max_elapsed_ms")
    @Serializable(with = ExecutionElapsedSerializer::class)
    val maxElapsedMillis: ElapsedTimeLimitMillis? = null,
    @SerialName("max_work_units")
    @Serializable(with = ExecutionWorkSerializer::class)
    val maxWorkUnits: WorkUnitLimit? = null,
    @SerialName("max_results")
    @Serializable(with = ExecutionResultsSerializer::class)
    val maxResults: ResultLimit? = null,
    @SerialName("max_returned_bytes")
    @Serializable(with = ExecutionBytesSerializer::class)
    val maxReturnedBytes: ReturnedByteLimit? = null,
) {
    fun requested() =
        RequestedExecutionBudget(
            maxElapsedMillis.allowance(),
            maxWorkUnits.allowance(),
            maxResults.allowance(),
            maxReturnedBytes.allowance(),
        )
}

private fun <Value : Any> Value?.allowance(): ExecutionAllowance<Value> =
    if (this == null) ExecutionAllowance.Default else ExecutionAllowance.Requested(this)

internal object ExecutionElapsedSerializer :
    RefiningLongSerializer<ElapsedTimeLimitMillis>("ExecutionElapsedMillis", 1) {
    override fun raw(value: ElapsedTimeLimitMillis) = value.value

    override fun refine(raw: Long) = ElapsedTimeLimitMillis.parse(raw)
}

internal object ExecutionWorkSerializer : RefiningLongSerializer<WorkUnitLimit>("ExecutionWorkUnits", 1) {
    override fun raw(value: WorkUnitLimit) = value.value

    override fun refine(raw: Long) = WorkUnitLimit.parse(raw)
}

internal object ExecutionResultsSerializer :
    RefiningIntSerializer<ResultLimit>("ExecutionResults", 1, Int.MAX_VALUE.toLong()) {
    override fun raw(value: ResultLimit) = value.value

    override fun refine(raw: Int) = ResultLimit.parse(raw)
}

internal object ExecutionBytesSerializer : RefiningLongSerializer<ReturnedByteLimit>("ExecutionReturnedBytes", 1) {
    override fun raw(value: ReturnedByteLimit) = value.value

    override fun refine(raw: Long) = ReturnedByteLimit.parse(raw)
}

@Serializable
enum class ExecutionBudgetSelectionDocument {
    @SerialName("configured_default") CONFIGURED_DEFAULT,
    @SerialName("caller") CALLER,
}

@Serializable
enum class ExecutionBudgetClampDocument {
    @SerialName("operator_ceiling") OPERATOR_CEILING,
    @SerialName("transport_capacity") TRANSPORT_CAPACITY,
    @SerialName("deadline_remaining") DEADLINE_REMAINING,
}

@Serializable
data class ExecutionLimitDocument
private constructor(
    val selection: ExecutionBudgetSelectionDocument,
    val requested: Long?,
    val configuredDefault: Long,
    val operatorCeiling: Long,
    val effective: Long,
    val clamping: List<ExecutionBudgetClampDocument>,
) {
    companion object {
        fun <Value> from(limit: AdmittedExecutionLimit<Value>, raw: (Value) -> Long): ExecutionLimitDocument {
            val selection =
                when (limit.requested) {
                    ExecutionAllowance.Default -> ExecutionBudgetSelectionDocument.CONFIGURED_DEFAULT
                    is ExecutionAllowance.Requested -> ExecutionBudgetSelectionDocument.CALLER
                }
            val requested =
                when (val request = limit.requested) {
                    ExecutionAllowance.Default -> null
                    is ExecutionAllowance.Requested -> raw(request.value)
                }
            return ExecutionLimitDocument(
                selection,
                requested,
                raw(limit.configuredDefault),
                raw(limit.operatorCeiling),
                raw(limit.effective),
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
        }
    }
}

/** A wire projection of admitted proof, never a second execution decision. */
@Serializable
data class ExecutionBudgetReport
private constructor(
    @SerialName("max_elapsed_ms") val elapsed: ExecutionLimitDocument,
    @SerialName("max_work_units") val work: ExecutionLimitDocument,
    @SerialName("max_results") val results: ExecutionLimitDocument,
    @SerialName("max_returned_bytes") val returnedBytes: ExecutionLimitDocument,
) {
    companion object {
        fun from(grant: AdmittedExecutionBudget) =
            ExecutionBudgetReport(
                ExecutionLimitDocument.from(grant.elapsed) { it.value },
                ExecutionLimitDocument.from(grant.work) { it.value },
                ExecutionLimitDocument.from(grant.results) { it.value.toLong() },
                ExecutionLimitDocument.from(grant.returnedBytes) { it.value },
            )
    }
}
