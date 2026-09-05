package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Active compiler operation whose target requires independent declaration binding. */
enum class TopologyIdentityStage { REFERENCE_TARGET, DIRECT_OVERRIDE }

/** Whether extraction ran in this read epoch or reused detached evidence. */
enum class TopologyCacheDisposition { COMPUTED, REUSED }

/** Native binding failures are independent of compiler type presentation. */
enum class TopologyBindingFailure {
    EPOCH_CHANGED,
    DECLARATION_UNAVAILABLE,
    ORIGIN_NOT_ADMITTED,
    ROLE_MISMATCH,
    MODULE_MISMATCH,
    DECLARATION_MISMATCH,
}

/** Detached context for a rejected native declaration proof. No rendered types or source text. */
data class TopologyIdentityMismatchEvidence(
    val stage: TopologyIdentityStage,
    val sourceFile: TopologySourceFile,
    val sourceOccurrence: ExactDeclarationTextRange,
    val targetFile: TopologySourceFile,
    val targetDeclarationRange: ExactDeclarationTextRange,
    val reason: TopologyBindingFailure,
)
