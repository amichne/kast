package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.PermanentIdentityFailure
import io.github.amichne.kast.kernel.Refinement

data class IdeHostCapability private constructor(val operation: CanonicalOperation) {

    companion object {
        /**
         * Proof transition: `OperationId -> Refinement<IdeHostCapability,
         * IdeHostCompatibilityFailure>`.
         *
         * Establishes membership in the canonical operation set. Hosted exposure is deliberately
         * not decided here: the canonical operation definitions are the sole authority and generated
         * compatibility metadata supplies its projection at the outer boundary.
         */
        internal fun resolve(
            operationId: OperationId,
        ): Refinement<IdeHostCapability, IdeHostCompatibilityFailure> =
            when (val resolution = CanonicalOperation.resolve(operationId)) {
                is CanonicalOperationResolution.Unknown -> Refinement.Rejected(
                    IdeHostCompatibilityFailure.UnknownCapability(resolution.id),
                )
                is CanonicalOperationResolution.Known ->
                    Refinement.Refined(byOperation.getValue(resolution.operation))
            }

        private val byOperation = CanonicalOperation.entries.associateWith(::IdeHostCapability)

        val INDEX_SYNC = byOperation.getValue(CanonicalOperation.INDEX_SYNC)
        val TOPOLOGY_BUILD = byOperation.getValue(CanonicalOperation.TOPOLOGY_BUILD)
        val SYMBOL_DISCOVER = byOperation.getValue(CanonicalOperation.SYMBOL_DISCOVER)
        val SYMBOL_INSPECT = byOperation.getValue(CanonicalOperation.SYMBOL_INSPECT)
        val SOURCE_READ = byOperation.getValue(CanonicalOperation.SOURCE_READ)
        val RELATION_READ = byOperation.getValue(CanonicalOperation.RELATION_READ)
        val TRAVERSAL_RUN = byOperation.getValue(CanonicalOperation.TRAVERSAL_RUN)
        val DIAGNOSTIC_CHECK = byOperation.getValue(CanonicalOperation.DIAGNOSTIC_CHECK)
        val CHANGE_PLAN = byOperation.getValue(CanonicalOperation.CHANGE_PLAN)
        val CHANGE_APPLY = byOperation.getValue(CanonicalOperation.CHANGE_APPLY)
        val CHANGE_RECOVER = byOperation.getValue(CanonicalOperation.CHANGE_RECOVER)

        /** Exact compatibility projection of the canonical public operation set. */
        val entries: List<IdeHostCapability> = CanonicalOperation.entries.map(byOperation::getValue)
    }
}

class IdeHostCapabilitySet private constructor(
    val capabilities: List<IdeHostCapability>,
) {
    override fun equals(other: Any?): Boolean =
        other is IdeHostCapabilitySet && capabilities == other.capabilities

    override fun hashCode(): Int = capabilities.hashCode()

    companion object {
        /**
         * Proof transition: `List<String> -> Refinement<IdeHostCapabilitySet, IdeHostCompatibilityFailure>`.
         *
         * Establishes an ordered, unique set of canonical operation identities. The exact public
         * set is supplied by generated canonical metadata and retained by
         * [IdeHostCompatibilityPolicy], rather than duplicated in this contract.
         */
        fun parse(raw: List<String>): Refinement<IdeHostCapabilitySet, IdeHostCompatibilityFailure> {
            val admitted = ArrayList<IdeHostCapability>(raw.size)
            val observed = LinkedHashSet<IdeHostCapability>()
            raw.forEach { identity ->
                val operationId = when (val parsed = OperationId.parse(identity)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(
                        IdeHostCompatibilityFailure.Malformed(
                            IdeHostCompatibilityField.CAPABILITIES,
                            parsed.failure.toCompatibilitySyntaxFailure(),
                        ),
                    )
                }
                val capability = when (val resolved = IdeHostCapability.resolve(operationId)) {
                    is Refinement.Refined -> resolved.value
                    is Refinement.Rejected -> return resolved
                }
                if (!observed.add(capability)) {
                    return Refinement.Rejected(
                        IdeHostCompatibilityFailure.DuplicateCapability(capability),
                    )
                }
                admitted += capability
            }
            return Refinement.Refined(IdeHostCapabilitySet(java.util.List.copyOf(admitted)))
        }
    }
}

private fun PermanentIdentityFailure.toCompatibilitySyntaxFailure():
    IdeHostCompatibilitySyntaxFailure = when (this) {
    PermanentIdentityFailure.BLANK -> IdeHostCompatibilitySyntaxFailure.BLANK
    PermanentIdentityFailure.TOO_LONG -> IdeHostCompatibilitySyntaxFailure.TOO_LONG
    PermanentIdentityFailure.INVALID_FORMAT -> IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT
}
