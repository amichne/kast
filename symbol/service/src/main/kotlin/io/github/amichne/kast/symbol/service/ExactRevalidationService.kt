package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort

/** Fresh reacquisition owns its own admission; strict exact resolution is unchanged. */
class ExactRevalidationService(
    private val validation: SemanticReadValidationPort,
    private val compiler: ExactRevalidationCompilerPort,
) : ExactRevalidationOperations {
    override suspend fun revalidate(
        locator: ExactRevalidationLocator,
        current: SemanticReadAuthority,
    ): ExactRevalidationResult {
        if (current.workspaceRoot != locator.root) return rejected(ExactRevalidationRejection.WORKSPACE_MISMATCH)
        if (current !is LiveSemanticReadAuthority || current.reference.host != locator.host)
            return rejected(ExactRevalidationRejection.OWNER_MISMATCH)
        when (validation.validate(current)) {
            SemanticReadValidation.CURRENT -> Unit
            SemanticReadValidation.ROOT_MISMATCH -> return rejected(ExactRevalidationRejection.WORKSPACE_MISMATCH)
            SemanticReadValidation.UNAVAILABLE -> return rejected(ExactRevalidationRejection.WORKSPACE_NOT_READY)
            SemanticReadValidation.MOVED -> return rejected(ExactRevalidationRejection.BASIS_MOVED)
        }
        val evidence =
            when (val result = compiler.confirm(locator, current)) {
                is ExactRevalidationCompilation.Confirmed -> result.evidence
                is ExactRevalidationCompilation.Rejected -> return rejected(result.reason)
            }
        if (evidence != locator.evidence) return rejected(ExactRevalidationRejection.COMPILER_IDENTITY_CHANGED)
        when (validation.validate(current)) {
            SemanticReadValidation.CURRENT -> Unit
            SemanticReadValidation.ROOT_MISMATCH -> return rejected(ExactRevalidationRejection.WORKSPACE_MISMATCH)
            SemanticReadValidation.UNAVAILABLE -> return rejected(ExactRevalidationRejection.WORKSPACE_NOT_READY)
            SemanticReadValidation.MOVED -> return rejected(ExactRevalidationRejection.BASIS_MOVED)
        }
        return ExactRevalidationResult.Reacquired(
            SymbolSelector.issue(current, locator.scope, evidence, locator.constraints)
        )
    }
}

private fun rejected(reason: ExactRevalidationRejection) = ExactRevalidationResult.Rejected(reason)
