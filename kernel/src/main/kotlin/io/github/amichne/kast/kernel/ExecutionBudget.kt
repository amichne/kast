package io.github.amichne.kast.kernel

/** Absence selects operator defaults; a caller value retains its positive proof. */
sealed interface ExecutionAllowance<out Value> {
    data object Default : ExecutionAllowance<Nothing>

    data class Requested<Value>(val value: Value) : ExecutionAllowance<Value>
}

@JvmInline
value class ReturnedByteLimit private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<ReturnedByteLimit, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(ReturnedByteLimit(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

data class RequestedExecutionBudget(
    val elapsed: ExecutionAllowance<ElapsedTimeLimitMillis> = ExecutionAllowance.Default,
    val work: ExecutionAllowance<WorkUnitLimit> = ExecutionAllowance.Default,
    val results: ExecutionAllowance<ResultLimit> = ExecutionAllowance.Default,
    val returnedBytes: ExecutionAllowance<ReturnedByteLimit> = ExecutionAllowance.Default,
)

enum class ExecutionBudgetClamp {
    OPERATOR_CEILING,
    TRANSPORT_CAPACITY,
    DEADLINE_REMAINING,
}

enum class ExecutionBudgetFailure {
    DEADLINE_EXHAUSTED
}

/** The transport supplies remaining time, never a process-local timestamp. */
data class ExecutionBudgetCapacity(
    val elapsed: ElapsedTimeLimitMillis,
    val results: ResultLimit,
    val returnedBytes: ReturnedByteLimit,
)

class AdmittedExecutionLimit<Value>
internal constructor(
    val requested: ExecutionAllowance<Value>,
    val configuredDefault: Value,
    val operatorCeiling: Value,
    val effective: Value,
    val clamping: Set<ExecutionBudgetClamp>,
)

/** One admission result accompanies all domain and encoding projections. */
class AdmittedExecutionBudget
private constructor(
    val elapsed: AdmittedExecutionLimit<ElapsedTimeLimitMillis>,
    val work: AdmittedExecutionLimit<WorkUnitLimit>,
    val results: AdmittedExecutionLimit<ResultLimit>,
    val returnedBytes: AdmittedExecutionLimit<ReturnedByteLimit>,
) {
    val resources = ResourceBudget(results.effective, work.effective, elapsed.effective)

    companion object {
        fun admit(
            request: RequestedExecutionBudget,
            defaults: ResourceBudget,
            defaultBytes: ReturnedByteLimit,
            ceilings: ResourceBudget,
            ceilingBytes: ReturnedByteLimit,
            capacity: ExecutionBudgetCapacity,
        ): AdmittedExecutionBudget =
            AdmittedExecutionBudget(
                selectExecutionLimit(
                    request.elapsed,
                    defaults.elapsedTimeLimit,
                    ceilings.elapsedTimeLimit,
                    capacity.elapsed,
                    ExecutionBudgetClamp.DEADLINE_REMAINING,
                ) {
                    it.value
                },
                selectExecutionLimit(
                    request.work,
                    defaults.workUnitLimit,
                    ceilings.workUnitLimit,
                    ceilings.workUnitLimit,
                    ExecutionBudgetClamp.OPERATOR_CEILING,
                ) {
                    it.value
                },
                selectExecutionLimit(
                    request.results,
                    defaults.resultLimit,
                    ceilings.resultLimit,
                    capacity.results,
                    ExecutionBudgetClamp.TRANSPORT_CAPACITY,
                ) {
                    it.value.toLong()
                },
                selectExecutionLimit(
                    request.returnedBytes,
                    defaultBytes,
                    ceilingBytes,
                    capacity.returnedBytes,
                    ExecutionBudgetClamp.TRANSPORT_CAPACITY,
                ) {
                    it.value
                },
            )
    }
}

private fun <Value> selectExecutionLimit(
    request: ExecutionAllowance<Value>,
    default: Value,
    ceiling: Value,
    capacity: Value,
    capacityCause: ExecutionBudgetClamp,
    raw: (Value) -> Long,
): AdmittedExecutionLimit<Value> {
    val selected =
        when (request) {
            ExecutionAllowance.Default -> default
            is ExecutionAllowance.Requested -> request.value
        }
    val effective = listOf(selected, ceiling, capacity).minBy(raw)
    val clamping = buildSet {
        if (raw(selected) > raw(ceiling)) add(ExecutionBudgetClamp.OPERATOR_CEILING)
        if (raw(selected) > raw(capacity)) add(capacityCause)
    }
    return AdmittedExecutionLimit(request, default, ceiling, effective, clamping)
}
