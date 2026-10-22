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

sealed interface RequestedExecutionBudget {
    val results: ExecutionAllowance<ResultLimit>
    val returnedBytes: ExecutionAllowance<ReturnedByteLimit>

    data class Bounded(
        val elapsed: ExecutionAllowance<ElapsedTimeLimitMillis> = ExecutionAllowance.Default,
        val work: ExecutionAllowance<WorkUnitLimit> = ExecutionAllowance.Default,
        override val results: ExecutionAllowance<ResultLimit> = ExecutionAllowance.Default,
        override val returnedBytes: ExecutionAllowance<ReturnedByteLimit> = ExecutionAllowance.Default,
    ) : RequestedExecutionBudget

    data class RunnerControlled(
        override val results: ExecutionAllowance<ResultLimit> = ExecutionAllowance.Default,
        override val returnedBytes: ExecutionAllowance<ReturnedByteLimit> = ExecutionAllowance.Default,
    ) : RequestedExecutionBudget

    companion object {
        operator fun invoke(
            elapsed: ExecutionAllowance<ElapsedTimeLimitMillis> = ExecutionAllowance.Default,
            work: ExecutionAllowance<WorkUnitLimit> = ExecutionAllowance.Default,
            results: ExecutionAllowance<ResultLimit> = ExecutionAllowance.Default,
            returnedBytes: ExecutionAllowance<ReturnedByteLimit> = ExecutionAllowance.Default,
        ): Bounded = Bounded(elapsed, work, results, returnedBytes)
    }
}

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
sealed interface AdmittedExecutionBudget {
    val results: AdmittedExecutionLimit<ResultLimit>
    val returnedBytes: AdmittedExecutionLimit<ReturnedByteLimit>
    val resources: ResourceBudget

    class Bounded
    internal constructor(
        val elapsed: AdmittedExecutionLimit<ElapsedTimeLimitMillis>,
        val work: AdmittedExecutionLimit<WorkUnitLimit>,
        override val results: AdmittedExecutionLimit<ResultLimit>,
        override val returnedBytes: AdmittedExecutionLimit<ReturnedByteLimit>,
    ) : AdmittedExecutionBudget {
        override val resources = ResourceBudget(results.effective, work.effective, elapsed.effective)
    }

    class RunnerControlled
    internal constructor(
        override val results: AdmittedExecutionLimit<ResultLimit>,
        override val returnedBytes: AdmittedExecutionLimit<ReturnedByteLimit>,
    ) : AdmittedExecutionBudget {
        override val resources = ResourceBudget.RunnerControlled
    }

    companion object {
        fun admit(
            request: RequestedExecutionBudget,
            defaults: ResourceBudget.Bounded,
            defaultBytes: ReturnedByteLimit,
            ceilings: ResourceBudget.Bounded,
            ceilingBytes: ReturnedByteLimit,
            capacity: ExecutionBudgetCapacity,
        ): AdmittedExecutionBudget =
            when (request) {
                is RequestedExecutionBudget.Bounded ->
                    admit(request, defaults, defaultBytes, ceilings, ceilingBytes, capacity)
                is RequestedExecutionBudget.RunnerControlled ->
                    RunnerControlled(
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

        fun admit(
            request: RequestedExecutionBudget.RunnerControlled,
            defaultResults: ResultLimit,
            defaultBytes: ReturnedByteLimit,
            ceilingResults: ResultLimit,
            ceilingBytes: ReturnedByteLimit,
            capacityResults: ResultLimit,
            capacityBytes: ReturnedByteLimit,
        ): RunnerControlled =
            RunnerControlled(
                selectExecutionLimit(
                    request.results,
                    defaultResults,
                    ceilingResults,
                    capacityResults,
                    ExecutionBudgetClamp.TRANSPORT_CAPACITY,
                ) {
                    it.value.toLong()
                },
                selectExecutionLimit(
                    request.returnedBytes,
                    defaultBytes,
                    ceilingBytes,
                    capacityBytes,
                    ExecutionBudgetClamp.TRANSPORT_CAPACITY,
                ) {
                    it.value
                },
            )

        fun admit(
            request: RequestedExecutionBudget.Bounded,
            defaults: ResourceBudget.Bounded,
            defaultBytes: ReturnedByteLimit,
            ceilings: ResourceBudget.Bounded,
            ceilingBytes: ReturnedByteLimit,
            capacity: ExecutionBudgetCapacity,
        ): Bounded =
            Bounded(
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
