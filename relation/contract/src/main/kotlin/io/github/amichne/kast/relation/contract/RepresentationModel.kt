package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class ModelIdentifierFailure {
    INVALID
}

/** Model-owned identity; names in source code never establish model identity. */
@JvmInline
value class ModelIdentifier private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ModelIdentifier, ModelIdentifierFailure> =
            if (
                raw.isNotEmpty() &&
                    raw.length <= MODEL_IDENTIFIER_MAX_LENGTH &&
                    raw.all { it.isModelIdentifierCharacter() }
            )
                Refinement.Refined(ModelIdentifier(raw))
            else Refinement.Rejected(ModelIdentifierFailure.INVALID)
    }
}

enum class ModelVersionFailure {
    NOT_POSITIVE
}

@JvmInline
value class ModelVersion private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<ModelVersion, ModelVersionFailure> =
            if (raw > 0) Refinement.Refined(ModelVersion(raw))
            else Refinement.Rejected(ModelVersionFailure.NOT_POSITIVE)
    }
}

/** A supplied review/source reference is modeled provenance, never compiler authority. */
data class ContractModelIdentity(val id: ModelIdentifier, val version: ModelVersion, val provenance: ModelIdentifier)

data class ModelRuleReference(val model: ContractModelIdentity, val rule: ModelIdentifier)

sealed interface ModelValuePosition {
    data object Result : ModelValuePosition

    data class Argument(val position: ValueArgumentPosition) : ModelValuePosition
}

data class ModelCallableReference(
    val basis: SemanticReadIdentity,
    val callable: CompilerSymbolIdentity,
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
    val position: ModelValuePosition,
)

enum class ModelBindingFailure {
    BASIS_MISMATCH,
    CALLABLE_MISMATCH,
    DECLARATION_MISMATCH,
    POSITION_UNAVAILABLE,
}

/** A position bound to exact freshly revalidated compiler declaration evidence on one basis. */
class ExactModelCallablePosition
private constructor(
    val endpoint: RelationEndpoint,
    val position: ModelValuePosition,
) {
    override fun equals(other: Any?): Boolean =
        other is ExactModelCallablePosition &&
            endpoint.lease.identity == other.endpoint.lease.identity &&
            ValueDeclarationIdentity(endpoint.compilerIdentity, endpoint.file, endpoint.range) ==
                ValueDeclarationIdentity(other.endpoint.compilerIdentity, other.endpoint.file, other.endpoint.range) &&
            position == other.position

    override fun hashCode(): Int =
        31 *
            (31 * endpoint.lease.identity.hashCode() +
                ValueDeclarationIdentity(endpoint.compilerIdentity, endpoint.file, endpoint.range).hashCode()) +
            position.hashCode()

    companion object {
        fun admit(
            declared: ModelCallableReference,
            current: RevalidatedRelationEndpoint,
        ): Refinement<ExactModelCallablePosition, ModelBindingFailure> {
            val endpoint = current.endpoint
            if (declared.basis != endpoint.lease.identity)
                return Refinement.Rejected(ModelBindingFailure.BASIS_MISMATCH)
            if (declared.callable != endpoint.compilerIdentity)
                return Refinement.Rejected(ModelBindingFailure.CALLABLE_MISMATCH)
            if (declared.file != endpoint.file || declared.range != endpoint.range)
                return Refinement.Rejected(ModelBindingFailure.DECLARATION_MISMATCH)
            val available =
                when (val signature = endpoint.signature) {
                    is CanonicalCompilerCallableSignature ->
                        when (val position = declared.position) {
                            ModelValuePosition.Result -> true
                            is ModelValuePosition.Argument -> position.position.value < signature.valueParameters.size
                        }
                    is CanonicalCompilerSignature.LocalProperty,
                    is CanonicalCompilerSignature.Property -> false
                    is CanonicalCompilerSignature.ClassLike,
                    is CanonicalCompilerSignature.TypeAlias -> false
                }
            if (!available) return Refinement.Rejected(ModelBindingFailure.POSITION_UNAVAILABLE)
            return Refinement.Refined(ExactModelCallablePosition(endpoint, declared.position))
        }
    }
}

enum class RepresentationDomainFailure {
    EMPTY_DOMAIN,
    DOMAIN_TOO_LARGE,
    DUPLICATE_STATE,
}

/** The small finite state vocabulary belongs to a versioned model, not Kast's encryption implementation. */
class RepresentationDomain private constructor(val model: ContractModelIdentity, val states: Set<ModelIdentifier>) {
    override fun equals(other: Any?): Boolean =
        other is RepresentationDomain && model == other.model && states == other.states

    override fun hashCode(): Int = 31 * model.hashCode() + states.hashCode()

    fun state(id: ModelIdentifier): Refinement<RepresentationState, RepresentationStateFailure> =
        if (id in states) Refinement.Refined(RepresentationState(this, id))
        else Refinement.Rejected(RepresentationStateFailure.UNDECLARED_STATE)

    companion object {
        fun admit(
            model: ContractModelIdentity,
            states: List<ModelIdentifier>,
        ): Refinement<RepresentationDomain, RepresentationDomainFailure> =
            when {
                states.isEmpty() -> Refinement.Rejected(RepresentationDomainFailure.EMPTY_DOMAIN)
                states.size > REPRESENTATION_DOMAIN_MAX_STATES ->
                    Refinement.Rejected(RepresentationDomainFailure.DOMAIN_TOO_LARGE)
                states.toSet().size != states.size -> Refinement.Rejected(RepresentationDomainFailure.DUPLICATE_STATE)
                else -> Refinement.Refined(RepresentationDomain(model, Collections.unmodifiableSet(states.toSet())))
            }
    }
}

enum class RepresentationStateFailure {
    UNDECLARED_STATE
}

@ConsistentCopyVisibility
data class RepresentationState internal constructor(val domain: RepresentationDomain, val id: ModelIdentifier)

enum class RepresentationRuleFailure {
    MODEL_MISMATCH,
    WRONG_POSITION,
    CALLABLE_MISMATCH,
    BASIS_MISMATCH,
}

/** Four finite model claims. Each binding retains its exact callable, position and semantic basis. */
sealed interface RepresentationRule {
    val reference: ModelRuleReference

    @ConsistentCopyVisibility
    data class Origin
    private constructor(
        override val reference: ModelRuleReference,
        val output: ExactModelCallablePosition,
        val state: RepresentationState,
    ) : RepresentationRule {
        companion object {
            fun admit(
                reference: ModelRuleReference,
                output: ExactModelCallablePosition,
                state: RepresentationState,
            ): Refinement<Origin, RepresentationRuleFailure> {
                if (reference.model != state.domain.model)
                    return Refinement.Rejected(RepresentationRuleFailure.MODEL_MISMATCH)
                if (output.position != ModelValuePosition.Result)
                    return Refinement.Rejected(RepresentationRuleFailure.WRONG_POSITION)
                return Refinement.Refined(Origin(reference, output, state))
            }
        }
    }

    @ConsistentCopyVisibility
    data class Transfer
    private constructor(
        override val reference: ModelRuleReference,
        val input: ExactModelCallablePosition,
        val output: ExactModelCallablePosition,
    ) : RepresentationRule {
        companion object {
            fun admit(
                reference: ModelRuleReference,
                input: ExactModelCallablePosition,
                output: ExactModelCallablePosition,
            ): Refinement<Transfer, RepresentationRuleFailure> =
                when (val admission = admitTransformationPositions(input, output)) {
                    is Refinement.Refined -> Refinement.Refined(Transfer(reference, input, output))
                    is Refinement.Rejected -> admission
                }
        }
    }

    @ConsistentCopyVisibility
    data class Transformation
    private constructor(
        override val reference: ModelRuleReference,
        val input: ExactModelCallablePosition,
        val output: ExactModelCallablePosition,
        val from: RepresentationState,
        val to: RepresentationState,
    ) : RepresentationRule {
        companion object {
            fun admit(
                reference: ModelRuleReference,
                input: ExactModelCallablePosition,
                output: ExactModelCallablePosition,
                from: RepresentationState,
                to: RepresentationState,
            ): Refinement<Transformation, RepresentationRuleFailure> {
                if (reference.model != from.domain.model || reference.model != to.domain.model)
                    return Refinement.Rejected(RepresentationRuleFailure.MODEL_MISMATCH)
                return when (val admission = admitTransformationPositions(input, output)) {
                    is Refinement.Refined -> Refinement.Refined(Transformation(reference, input, output, from, to))
                    is Refinement.Rejected -> admission
                }
            }
        }
    }

    @ConsistentCopyVisibility
    data class ConsumerExpectation
    private constructor(
        override val reference: ModelRuleReference,
        val input: ExactModelCallablePosition,
        val expected: RepresentationState,
    ) : RepresentationRule {
        companion object {
            fun admit(
                reference: ModelRuleReference,
                input: ExactModelCallablePosition,
                expected: RepresentationState,
            ): Refinement<ConsumerExpectation, RepresentationRuleFailure> {
                if (reference.model != expected.domain.model)
                    return Refinement.Rejected(RepresentationRuleFailure.MODEL_MISMATCH)
                if (input.position == ModelValuePosition.Result)
                    return Refinement.Rejected(RepresentationRuleFailure.WRONG_POSITION)
                return Refinement.Refined(ConsumerExpectation(reference, input, expected))
            }
        }
    }
}

private fun admitTransformationPositions(
    input: ExactModelCallablePosition,
    output: ExactModelCallablePosition,
): Refinement<Unit, RepresentationRuleFailure> =
    when {
        input.position == ModelValuePosition.Result || output.position != ModelValuePosition.Result ->
            Refinement.Rejected(RepresentationRuleFailure.WRONG_POSITION)
        input.endpoint.lease.identity != output.endpoint.lease.identity ->
            Refinement.Rejected(RepresentationRuleFailure.BASIS_MISMATCH)
        input.endpoint.compilerIdentity != output.endpoint.compilerIdentity ||
            input.endpoint.file != output.endpoint.file ||
            input.endpoint.range != output.endpoint.range ->
            Refinement.Rejected(RepresentationRuleFailure.CALLABLE_MISMATCH)
        else -> Refinement.Refined(Unit)
    }

private const val MODEL_IDENTIFIER_MAX_LENGTH = 128
private const val REPRESENTATION_DOMAIN_MAX_STATES = 32

private fun Char.isModelIdentifierCharacter(): Boolean =
    isAsciiIdentifierLetter() || this in '0'..'9' || this in "._:/-"

private fun Char.isAsciiIdentifierLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
