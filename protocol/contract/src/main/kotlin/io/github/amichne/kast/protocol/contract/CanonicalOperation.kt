package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement

/** The complete and only public operation identity set. */
enum class CanonicalOperation(
    val id: OperationId,
) {
    WORKSPACE_INSPECT(canonicalOperationId("workspace.inspect")),
    INDEX_SYNC(canonicalOperationId("index.sync")),
    TOPOLOGY_BUILD(canonicalOperationId("topology.build")),
    SYMBOL_DISCOVER(canonicalOperationId("symbol.discover")),
    SYMBOL_RESOLVE(canonicalOperationId("symbol.resolve")),
    SYMBOL_DESCRIBE(canonicalOperationId("symbol.describe")),
    SOURCE_READ(canonicalOperationId("source.read")),
    RELATION_READ(canonicalOperationId("relation.read")),
    TRAVERSAL_RUN(canonicalOperationId("traversal.run")),
    DIAGNOSTIC_CHECK(canonicalOperationId("diagnostic.check")),
    CHANGE_PLAN(canonicalOperationId("change.plan")),
    CHANGE_APPLY(canonicalOperationId("change.apply")),
    CHANGE_VERIFY(canonicalOperationId("change.verify")),
    CHANGE_RECOVER(canonicalOperationId("change.recover")),
    ;

    companion object {
        private val byId: Map<OperationId, CanonicalOperation> = entries.associateBy { it.id }

        /**
         * Resolves a refined permanent identity against the closed public operation set.
         *
         * Unknown identities remain [CanonicalOperationResolution.Unknown] and cannot acquire
         * canonical operation authority.
         */
        fun resolve(id: OperationId): CanonicalOperationResolution =
            byId[id]
                ?.let(CanonicalOperationResolution::Known)
            ?: CanonicalOperationResolution.Unknown(id)
    }
}

sealed interface CanonicalOperationResolution {
    data class Known(
        val operation: CanonicalOperation,
    ) : CanonicalOperationResolution

    data class Unknown(
        val id: OperationId,
    ) : CanonicalOperationResolution
}

private fun canonicalOperationId(literal: String): OperationId = when (
    val refined = OperationId.parse(literal)
) {
    is Refinement.Refined -> refined.value
    is Refinement.Rejected -> error("Invalid canonical operation literal: ${refined.failure}")
}
