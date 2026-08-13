package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.ResourceBudget

/**
 * Closed common reasons that prevent an operation from being admitted.
 *
 * Operation-specific rejection types may retain one of these blockers without converting it to
 * text or gaining authority to resolve it.
 */
sealed interface OperationBlocker {
    data class CapabilityUnavailable(
        val requiredCapability: CapabilityId,
    ) : OperationBlocker

    data class ScopeUnavailable(
        val requiredScope: OperationScope,
    ) : OperationBlocker

    data class BudgetUnavailable(
        val requested: ResourceBudget,
        val available: ResourceBudget,
    ) : OperationBlocker

    data class StrongerOperationRequired(
        val requiredOperation: OperationId,
    ) : OperationBlocker
}
