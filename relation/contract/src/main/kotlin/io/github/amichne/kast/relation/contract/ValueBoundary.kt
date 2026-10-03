package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class BoundaryKind {
    SERIALIZATION,
    PERSISTENCE,
    EXTERNAL_SYSTEM,
}

data class BoundaryContractIdentity(val id: ModelIdentifier, val version: ModelVersion)

/** Transport data selects an exact site and versioned contract; a slot name alone is never identity. */
data class BoundaryPositionReference(
    val site: ValueSiteIdentity,
    val basis: SemanticReadIdentity,
    val kind: BoundaryKind,
    val contract: BoundaryContractIdentity,
    val slot: ModelIdentifier,
)

@ConsistentCopyVisibility
data class BoundaryPosition
private constructor(
    val site: ValueSite,
    val kind: BoundaryKind,
    val contract: BoundaryContractIdentity,
    val slot: ModelIdentifier,
) {
    val reference = BoundaryPositionReference(site.identity, site.basis, kind, contract, slot)

    companion object {
        /** Attaches supplied model vocabulary to an already source-proven value position. */
        fun at(site: ValueSite, kind: BoundaryKind, contract: BoundaryContractIdentity, slot: ModelIdentifier) =
            BoundaryPosition(site, kind, contract, slot)
    }
}

enum class BoundaryCompatibilityAssumption {
    CONTRACT_COMPATIBLE,
    REPRESENTATION_PRESERVED,
}

enum class BoundaryTerminalMeaning {
    REVIEWED_DISPOSAL,
    REVIEWED_EXTERNAL_SINK,
    REVIEWED_RETENTION,
}

enum class BoundaryModelFailure {
    SOURCE_MISMATCH,
    TARGET_MISMATCH,
    BASIS_MISMATCH,
    KIND_MISMATCH,
}

/** Explicit reviewed model evidence. This never creates a compiler edge or a shared cross-repository epoch. */
sealed interface BoundaryModel {
    val reference: ModelRuleReference
    val source: BoundaryPosition

    @ConsistentCopyVisibility
    data class Continuation
    private constructor(
        override val reference: ModelRuleReference,
        override val source: BoundaryPosition,
        val target: BoundaryPosition,
        val assumptions: Set<BoundaryCompatibilityAssumption>,
    ) : BoundaryModel {
        companion object {
            fun admit(
                reference: ModelRuleReference,
                declaredSource: BoundaryPositionReference,
                declaredTarget: BoundaryPositionReference,
                currentSource: BoundaryPosition,
                currentTarget: BoundaryPosition,
                assumptions: Set<BoundaryCompatibilityAssumption>,
            ): Refinement<Continuation, BoundaryModelFailure> {
                if (
                    declaredSource.basis != currentSource.site.basis || declaredTarget.basis != currentTarget.site.basis
                )
                    return Refinement.Rejected(BoundaryModelFailure.BASIS_MISMATCH)
                if (declaredSource != currentSource.reference)
                    return Refinement.Rejected(BoundaryModelFailure.SOURCE_MISMATCH)
                if (declaredTarget != currentTarget.reference)
                    return Refinement.Rejected(BoundaryModelFailure.TARGET_MISMATCH)
                if (currentSource.kind != currentTarget.kind)
                    return Refinement.Rejected(BoundaryModelFailure.KIND_MISMATCH)
                return Refinement.Refined(
                    Continuation(
                        reference,
                        currentSource,
                        currentTarget,
                        Collections.unmodifiableSet(assumptions.toSet()),
                    )
                )
            }
        }
    }

    @ConsistentCopyVisibility
    data class Terminal
    private constructor(
        override val reference: ModelRuleReference,
        override val source: BoundaryPosition,
        val meaning: BoundaryTerminalMeaning,
    ) : BoundaryModel {
        companion object {
            fun admit(
                reference: ModelRuleReference,
                declaredSource: BoundaryPositionReference,
                currentSource: BoundaryPosition,
                meaning: BoundaryTerminalMeaning,
            ): Refinement<Terminal, BoundaryModelFailure> =
                when {
                    declaredSource.basis != currentSource.site.basis ->
                        Refinement.Rejected(BoundaryModelFailure.BASIS_MISMATCH)
                    declaredSource != currentSource.reference ->
                        Refinement.Rejected(BoundaryModelFailure.SOURCE_MISMATCH)
                    meaning == BoundaryTerminalMeaning.REVIEWED_RETENTION &&
                        currentSource.kind != BoundaryKind.PERSISTENCE ->
                        Refinement.Rejected(BoundaryModelFailure.KIND_MISMATCH)
                    meaning == BoundaryTerminalMeaning.REVIEWED_EXTERNAL_SINK &&
                        currentSource.kind != BoundaryKind.EXTERNAL_SYSTEM ->
                        Refinement.Rejected(BoundaryModelFailure.KIND_MISMATCH)
                    else -> Refinement.Refined(Terminal(reference, currentSource, meaning))
                }
        }
    }
}

enum class BoundaryUnresolvedReason {
    MISSING_MODEL,
    INVALID_MODEL,
    STALE_MODEL,
    MISSING_CONSUMER,
}

enum class BoundaryRequiredEvidence {
    REVIEWED_MODEL,
    EXACT_DOWNSTREAM_POSITION,
    CORRECTED_MODEL,
    CURRENT_BASIS_REVALIDATION,
    RETENTION_POLICY,
    DECODING_COMPATIBILITY,
    MIGRATION_PROOF,
}

/** Every unresolved boundary names finite evidence requirements at an exact position. */
@ConsistentCopyVisibility
data class BoundaryObligation
private constructor(val position: BoundaryPosition, val required: Set<BoundaryRequiredEvidence>) {
    companion object {
        internal fun unresolved(position: BoundaryPosition, reason: BoundaryUnresolvedReason): BoundaryObligation =
            create(
                position,
                when (reason) {
                    BoundaryUnresolvedReason.MISSING_MODEL ->
                        setOf(
                            BoundaryRequiredEvidence.REVIEWED_MODEL,
                            BoundaryRequiredEvidence.EXACT_DOWNSTREAM_POSITION,
                        )
                    BoundaryUnresolvedReason.INVALID_MODEL -> setOf(BoundaryRequiredEvidence.CORRECTED_MODEL)
                    BoundaryUnresolvedReason.STALE_MODEL -> setOf(BoundaryRequiredEvidence.CURRENT_BASIS_REVALIDATION)
                    BoundaryUnresolvedReason.MISSING_CONSUMER ->
                        setOf(BoundaryRequiredEvidence.EXACT_DOWNSTREAM_POSITION)
                } + persistenceRequirements(position),
            )

        internal fun persistence(position: BoundaryPosition): List<BoundaryObligation> =
            if (position.kind == BoundaryKind.PERSISTENCE) listOf(create(position, persistenceRequirements(position)))
            else emptyList()

        private fun create(position: BoundaryPosition, required: Set<BoundaryRequiredEvidence>) =
            BoundaryObligation(position, Collections.unmodifiableSet(required.toSet()))

        private fun persistenceRequirements(position: BoundaryPosition): Set<BoundaryRequiredEvidence> =
            when (position.kind) {
                BoundaryKind.SERIALIZATION,
                BoundaryKind.EXTERNAL_SYSTEM -> emptySet()
                BoundaryKind.PERSISTENCE ->
                    setOf(
                        BoundaryRequiredEvidence.RETENTION_POLICY,
                        BoundaryRequiredEvidence.DECODING_COMPATIBILITY,
                        BoundaryRequiredEvidence.MIGRATION_PROOF,
                    )
            }
    }
}

/**
 * Exactly one outcome owns a boundary arrival. Connected and reviewed terminal still retain persistence obligations.
 */
sealed interface BoundaryArrival {
    val source: BoundaryPosition
    val obligations: List<BoundaryObligation>

    @ConsistentCopyVisibility
    data class Connected
    private constructor(
        override val source: BoundaryPosition,
        val model: BoundaryModel.Continuation,
    ) : BoundaryArrival {
        override val obligations = BoundaryObligation.persistence(source)
        val target: BoundaryPosition
            get() = model.target

        companion object {
            internal fun create(source: BoundaryPosition, model: BoundaryModel.Continuation) = Connected(source, model)
        }
    }

    class Terminal private constructor(override val source: BoundaryPosition, val model: BoundaryModel.Terminal) :
        BoundaryArrival {
        override val obligations = BoundaryObligation.persistence(source)

        companion object {
            internal fun create(source: BoundaryPosition, model: BoundaryModel.Terminal) = Terminal(source, model)
        }
    }

    class Unresolved private constructor(override val source: BoundaryPosition, val reason: BoundaryUnresolvedReason) :
        BoundaryArrival {
        val obligation = BoundaryObligation.unresolved(source, reason)
        override val obligations = listOf(obligation)

        companion object {
            internal fun create(source: BoundaryPosition, reason: BoundaryUnresolvedReason) = Unresolved(source, reason)
        }
    }

    companion object {
        fun connect(
            source: BoundaryPosition,
            model: BoundaryModel.Continuation,
        ): Refinement<Connected, BoundaryModelFailure> =
            if (source.reference == model.source.reference) Refinement.Refined(Connected.create(source, model))
            else Refinement.Rejected(BoundaryModelFailure.SOURCE_MISMATCH)

        fun terminal(
            source: BoundaryPosition,
            model: BoundaryModel.Terminal,
        ): Refinement<Terminal, BoundaryModelFailure> =
            if (source.reference == model.source.reference) Refinement.Refined(Terminal.create(source, model))
            else Refinement.Rejected(BoundaryModelFailure.SOURCE_MISMATCH)

        fun unresolved(source: BoundaryPosition, reason: BoundaryUnresolvedReason): Unresolved =
            Unresolved.create(source, reason)
    }
}
