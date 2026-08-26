package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.PermanentIdentityFailure
import io.github.amichne.kast.kernel.Refinement

enum class IdeHostCapability(val operation: CanonicalOperation) {
    WORKSPACE_INSPECT(CanonicalOperation.WORKSPACE_INSPECT),
    SYMBOL_DISCOVER(CanonicalOperation.SYMBOL_DISCOVER),
    SYMBOL_RESOLVE(CanonicalOperation.SYMBOL_RESOLVE),
    SYMBOL_DESCRIBE(CanonicalOperation.SYMBOL_DESCRIBE),
    ;

    companion object {
        /**
         * Proof transition: `OperationId -> Refinement<IdeHostCapability,
         * IdeHostCompatibilityFailure>`.
         *
         * Establishes membership in the exact four-operation IDE-hosted read capability set.
         * Unknown and canonical-but-unsupported operations remain distinct closed failures. Raw
         * operation text may be extracted only before `OperationId` parsing at the report or
         * endpoint boundary.
         */
        internal fun resolve(
            operationId: OperationId,
        ): Refinement<IdeHostCapability, IdeHostCompatibilityFailure> =
            when (val resolution = CanonicalOperation.resolve(operationId)) {
                is CanonicalOperationResolution.Unknown -> Refinement.Rejected(
                    IdeHostCompatibilityFailure.UnknownCapability(resolution.id),
                )
                is CanonicalOperationResolution.Known -> when (resolution.operation) {
                    CanonicalOperation.WORKSPACE_INSPECT -> Refinement.Refined(WORKSPACE_INSPECT)
                    CanonicalOperation.SYMBOL_DISCOVER -> Refinement.Refined(SYMBOL_DISCOVER)
                    CanonicalOperation.SYMBOL_RESOLVE -> Refinement.Refined(SYMBOL_RESOLVE)
                    CanonicalOperation.SYMBOL_DESCRIBE -> Refinement.Refined(SYMBOL_DESCRIBE)
                    CanonicalOperation.TOPOLOGY_BUILD,
                    CanonicalOperation.RELATION_READ,
                    CanonicalOperation.TRAVERSAL_RUN,
                    CanonicalOperation.DIAGNOSTIC_CHECK,
                    CanonicalOperation.CHANGE_PLAN,
                    CanonicalOperation.CHANGE_APPLY,
                    CanonicalOperation.CHANGE_VERIFY,
                    CanonicalOperation.CHANGE_RECOVER,
                    -> Refinement.Rejected(
                        IdeHostCompatibilityFailure.UnsupportedCapability(resolution.operation),
                    )
                }
            }
    }
}

class IdeHostCapabilitySet private constructor() {
    val capabilities: List<IdeHostCapability>
        get() = IdeHostCapability.entries.toList()

    companion object {
        private val exactCapabilities = IdeHostCapability.entries.toList()

        /**
         * Proof transition: `List<String> -> Refinement<IdeHostCapabilitySet, IdeHostCompatibilityFailure>`.
         *
         * Establishes the exact ordered, unique four-operation IDE-hosted read set derived from
         * [CanonicalOperation]. Unknown, unsupported, duplicate, missing, extra, and reordered
         * values are closed [IdeHostCompatibilityFailure] cases. Raw identities may be extracted
         * only at endpoint or generated-report boundaries.
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
            return if (admitted == exactCapabilities) {
                Refinement.Refined(IdeHostCapabilitySet())
            } else {
                Refinement.Rejected(IdeHostCompatibilityFailure.CapabilitySetMismatch)
            }
        }
    }
}

private fun PermanentIdentityFailure.toCompatibilitySyntaxFailure():
    IdeHostCompatibilitySyntaxFailure = when (this) {
    PermanentIdentityFailure.BLANK -> IdeHostCompatibilitySyntaxFailure.BLANK
    PermanentIdentityFailure.TOO_LONG -> IdeHostCompatibilitySyntaxFailure.TOO_LONG
    PermanentIdentityFailure.INVALID_FORMAT -> IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT
}
