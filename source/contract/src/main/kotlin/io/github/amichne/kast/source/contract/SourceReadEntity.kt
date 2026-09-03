package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CandidateSelector

enum class SourceRegionFailure {
    SELECTOR_IS_ENTITY,
    KIND_MISMATCH,
}

/** One selected structural region retaining its exact reusable source authority. */
class SourceRegion private constructor(
    val kind: SourceRegionKind,
    val selector: SourceSelector,
) {
    companion object {
        fun create(
            kind: SourceRegionKind,
            selector: SourceSelector,
        ): Refinement<SourceRegion, SourceRegionFailure> {
            val selectorKind = when (selector) {
                is SourceSelector.RootRegion -> selector.kind
                is SourceSelector.NestedRegion -> selector.kind
                is SourceSelector.Entity ->
                    return Refinement.Rejected(SourceRegionFailure.SELECTOR_IS_ENTITY)
            }
            return if (selectorKind != kind) {
                Refinement.Rejected(SourceRegionFailure.KIND_MISMATCH)
            } else {
                Refinement.Refined(SourceRegion(kind, selector))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SourceRegion && kind == other.kind && selector.fingerprint == other.selector.fingerprint

    override fun hashCode(): Int = 31 * kind.hashCode() + selector.fingerprint.hashCode()
}

enum class SourceNestingDepthFailure {
    NEGATIVE,
}

@JvmInline
value class SourceNestingDepth private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<SourceNestingDepth, SourceNestingDepthFailure> =
            if (raw < 0) {
                Refinement.Rejected(SourceNestingDepthFailure.NEGATIVE)
            } else {
                Refinement.Refined(SourceNestingDepth(raw))
            }
    }
}

sealed interface DeclarationSemanticIdentity {
    data class Candidate(
        val selector: CandidateSelector.Declaration,
    ) : DeclarationSemanticIdentity
}

enum class CompilerUnresolvedReason {
    NAME_NOT_FOUND,
    AMBIGUOUS,
    ERROR_TYPE,
    UNSUPPORTED_TARGET,
}

sealed interface SourceEntityTarget {
    data class Candidate(val selector: CandidateSelector.Declaration) : SourceEntityTarget
    data class Local(val selector: SourceSelector) : SourceEntityTarget
    data class Unresolved(val reason: CompilerUnresolvedReason) : SourceEntityTarget
}

enum class SourceEntityFailure {
    KIND_MISMATCH,
    CALLEE_PARENT_MISMATCH,
}

/** Closed structural entities returned from one selected source region. */
sealed interface SourceEntity {
    val selector: SourceSelector.Entity
    val parentSelector: SourceSelector
    val nestingDepth: SourceNestingDepth

    class Declaration private constructor(
        override val selector: SourceSelector.Entity,
        override val nestingDepth: SourceNestingDepth,
        val kind: DeclarationKind,
        val visibility: DeclarationVisibility,
        val semanticIdentity: DeclarationSemanticIdentity,
    ) : SourceEntity {
        override val parentSelector: SourceSelector = selector.parent

        companion object {
            fun create(
                selector: SourceSelector.Entity,
                nestingDepth: SourceNestingDepth,
                kind: DeclarationKind,
                visibility: DeclarationVisibility,
                semanticIdentity: DeclarationSemanticIdentity,
            ): Refinement<Declaration, SourceEntityFailure> =
                if (selector.kind != kind.sourceEntityKind()) {
                    Refinement.Rejected(SourceEntityFailure.KIND_MISMATCH)
                } else {
                    Refinement.Refined(
                        Declaration(selector, nestingDepth, kind, visibility, semanticIdentity),
                    )
                }
        }
    }

    class ValueParameter private constructor(
        override val selector: SourceSelector.Entity,
        override val nestingDepth: SourceNestingDepth,
    ) : SourceEntity {
        override val parentSelector: SourceSelector = selector.parent

        companion object {
            fun create(
                selector: SourceSelector.Entity,
                nestingDepth: SourceNestingDepth,
            ): Refinement<ValueParameter, SourceEntityFailure> =
                if (selector.kind != SourceEntityKind.VALUE_PARAMETER) {
                    Refinement.Rejected(SourceEntityFailure.KIND_MISMATCH)
                } else {
                    Refinement.Refined(ValueParameter(selector, nestingDepth))
                }
        }
    }

    class Call private constructor(
        override val selector: SourceSelector.Entity,
        override val nestingDepth: SourceNestingDepth,
        val calleeSelector: SourceSelector.Entity,
        val target: SourceEntityTarget,
    ) : SourceEntity {
        override val parentSelector: SourceSelector = selector.parent

        companion object {
            fun create(
                selector: SourceSelector.Entity,
                nestingDepth: SourceNestingDepth,
                calleeSelector: SourceSelector.Entity,
                target: SourceEntityTarget,
            ): Refinement<Call, SourceEntityFailure> = when {
                selector.kind != SourceEntityKind.CALL ||
                    calleeSelector.kind != SourceEntityKind.CALLEE ->
                    Refinement.Rejected(SourceEntityFailure.KIND_MISMATCH)
                calleeSelector.parent.fingerprint != selector.fingerprint ->
                    Refinement.Rejected(SourceEntityFailure.CALLEE_PARENT_MISMATCH)
                else -> Refinement.Refined(Call(selector, nestingDepth, calleeSelector, target))
            }
        }
    }

    class Reference private constructor(
        override val selector: SourceSelector.Entity,
        override val nestingDepth: SourceNestingDepth,
        val target: SourceEntityTarget,
    ) : SourceEntity {
        override val parentSelector: SourceSelector = selector.parent

        companion object {
            fun create(
                selector: SourceSelector.Entity,
                nestingDepth: SourceNestingDepth,
                target: SourceEntityTarget,
            ): Refinement<Reference, SourceEntityFailure> =
                if (selector.kind != SourceEntityKind.REFERENCE) {
                    Refinement.Rejected(SourceEntityFailure.KIND_MISMATCH)
                } else {
                    Refinement.Refined(Reference(selector, nestingDepth, target))
                }
        }
    }
}

private fun DeclarationKind.sourceEntityKind(): SourceEntityKind = when (this) {
    DeclarationKind.CLASSLIKE -> SourceEntityKind.DECLARATION_CLASSLIKE
    DeclarationKind.CONSTRUCTOR -> SourceEntityKind.DECLARATION_CONSTRUCTOR
    DeclarationKind.FUNCTION -> SourceEntityKind.DECLARATION_FUNCTION
    DeclarationKind.PROPERTY -> SourceEntityKind.DECLARATION_PROPERTY
    DeclarationKind.TYPE_ALIAS -> SourceEntityKind.DECLARATION_TYPE_ALIAS
}
