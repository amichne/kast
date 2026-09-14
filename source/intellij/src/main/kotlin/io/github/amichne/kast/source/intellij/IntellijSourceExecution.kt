package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ResourceBudget

internal enum class SourceExecutionAdmission {
    ADMITTED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    CLOCK_REJECTED,
}

/** Request-local accounting; detached facts remain owned by each individual read attempt. */
internal class IntellijSourceExecution(
    private val resources: ResourceBudget,
    private val limits: ReadLimits = ReadLimits.Default,
    private val clock: () -> Long = System::nanoTime,
) {
    private var examined = 0L

    fun admitUnit(): SourceExecutionAdmission {
        if (examined == limits[ReadLimitParameter.SOURCE_ENTITY_WORK].value.toLong()) {
            return SourceExecutionAdmission.WORK_LIMIT_REACHED
        }
        examined += 1
        return SourceExecutionAdmission.ADMITTED
    }

    fun observeCompletion(): SourceExecutionAdmission = SourceExecutionAdmission.ADMITTED
}
