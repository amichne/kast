package io.github.amichne.kast.idea.proofloss

import io.github.amichne.kast.api.contract.NonEmptyList
import io.github.amichne.kast.shared.proofloss.model.ArgumentIndex
import io.github.amichne.kast.shared.proofloss.model.ArgumentIndexParseResult
import io.github.amichne.kast.shared.proofloss.model.BoundaryDescriptor
import io.github.amichne.kast.shared.proofloss.model.BoundaryId
import io.github.amichne.kast.shared.proofloss.model.MaterializerDescriptor
import io.github.amichne.kast.shared.proofloss.model.ModelBuildResult
import io.github.amichne.kast.shared.proofloss.model.ModelViolation
import io.github.amichne.kast.shared.proofloss.model.Obligation
import io.github.amichne.kast.shared.proofloss.model.PredicateDescriptor
import io.github.amichne.kast.shared.proofloss.model.PredicateId
import io.github.amichne.kast.shared.proofloss.model.ProofModel
import org.jetbrains.kotlin.psi.KtNamedFunction

internal enum class MaterializerKind { TOTAL, NULLABLE_WITH_EXIT }

internal data class ModelSpec(
    val predicates: List<Predicate>,
    val boundaries: List<Boundary>,
) {
    data class Predicate(
        val id: PredicateId,
        val declaration: KtNamedFunction,
        val subjectIndex: Int,
        val materializers: List<Materializer>,
    )

    data class Materializer(
        val declaration: KtNamedFunction,
        val kind: MaterializerKind,
    )

    data class Boundary(
        val id: BoundaryId,
        val declaration: KtNamedFunction,
        val obligations: List<Pair<Int, PredicateId>>,
    )
}

internal sealed interface ModelResolution {
    data class Resolved(val model: ProofModel) : ModelResolution
    data class Rejected(val failures: NonEmptyList<ModelResolutionFailure>) : ModelResolution
}

internal sealed interface ModelResolutionFailure {
    data class UnresolvedCallable(val declaration: KtNamedFunction) : ModelResolutionFailure
    data class InvalidArgumentIndex(val value: Int) : ModelResolutionFailure
    data class InvalidModel(val violations: NonEmptyList<ModelViolation>) : ModelResolutionFailure
}

internal fun ModelSpec.resolve(): ModelResolution = ModelCandidates(
    predicates.map(ModelSpec.Predicate::resolve).sequence(),
    boundaries.map(ModelSpec.Boundary::resolve).sequence(),
)
    .toModelPart()
    .toModelResolution()

private fun ModelSpec.Predicate.resolve(): ModelPart<PredicateDescriptor> =
    declaration.toCallableKey()
        ?.let { callable ->
            subjectIndex.resolveArgumentIndex().flatMap { subjectIndex ->
                materializers
                    .map(ModelSpec.Materializer::resolve)
                    .sequence()
                    .map { materializers -> PredicateDescriptor(id, callable, subjectIndex, materializers.toSet()) }
            }
        }
        ?: ModelPart.Rejected(listOf(ModelResolutionFailure.UnresolvedCallable(declaration)))

private fun ModelSpec.Materializer.resolve(): ModelPart<MaterializerDescriptor> =
    declaration.toCallableKey()
        ?.let { callable ->
            ModelPart.Resolved(
                when (kind) {
                    MaterializerKind.TOTAL -> MaterializerDescriptor.Total(callable)
                    MaterializerKind.NULLABLE_WITH_EXIT -> MaterializerDescriptor.NullableWithExit(callable)
                },
            )
        }
        ?: ModelPart.Rejected(listOf(ModelResolutionFailure.UnresolvedCallable(declaration)))

private fun ModelSpec.Boundary.resolve(): ModelPart<BoundaryDescriptor> =
    declaration.toCallableKey()
        ?.let { callable ->
            obligations
                .map { (argument, predicate) ->
                    argument.resolveArgumentIndex().map { Obligation(it, predicate) }
                }
                .sequence()
                .map { obligations -> BoundaryDescriptor(id, callable, obligations) }
        }
        ?: ModelPart.Rejected(listOf(ModelResolutionFailure.UnresolvedCallable(declaration)))

private fun Int.resolveArgumentIndex(): ModelPart<ArgumentIndex> = when (val result = ArgumentIndex.parse(this)) {
    is ArgumentIndexParseResult.Valid -> ModelPart.Resolved(result.value)
    is ArgumentIndexParseResult.Negative ->
        ModelPart.Rejected(listOf(ModelResolutionFailure.InvalidArgumentIndex(result.value)))
}

private sealed interface ModelPart<out T> {
    data class Resolved<T>(val value: T) : ModelPart<T>
    data class Rejected(val failures: List<ModelResolutionFailure>) : ModelPart<Nothing>
}

private inline fun <T, R> ModelPart<T>.map(transform: (T) -> R): ModelPart<R> = when (this) {
    is ModelPart.Resolved -> ModelPart.Resolved(transform(value))
    is ModelPart.Rejected -> this
}

private inline fun <T, R> ModelPart<T>.flatMap(transform: (T) -> ModelPart<R>): ModelPart<R> = when (this) {
    is ModelPart.Resolved -> transform(value)
    is ModelPart.Rejected -> this
}

private fun <T> List<ModelPart<T>>.sequence(): ModelPart<List<T>> =
    flatMap { part ->
        when (part) {
            is ModelPart.Resolved -> emptyList()
            is ModelPart.Rejected -> part.failures
        }
    }.let { failures ->
        if (failures.isEmpty()) {
            ModelPart.Resolved(
                mapNotNull { part ->
                    when (part) {
                        is ModelPart.Resolved -> part.value
                        is ModelPart.Rejected -> null
                    }
                },
            )
        } else ModelPart.Rejected(failures)
    }

private data class ModelCandidates(
    val predicates: ModelPart<List<PredicateDescriptor>>,
    val boundaries: ModelPart<List<BoundaryDescriptor>>,
) {
    fun toModelPart(): ModelPart<Pair<List<PredicateDescriptor>, List<BoundaryDescriptor>>> =
        if (predicates is ModelPart.Resolved && boundaries is ModelPart.Resolved) {
            ModelPart.Resolved(predicates.value to boundaries.value)
        } else ModelPart.Rejected(predicates.failures() + boundaries.failures())
}

private fun ModelPart<*>.failures(): List<ModelResolutionFailure> = when (this) {
    is ModelPart.Resolved -> emptyList()
    is ModelPart.Rejected -> failures
}

private fun ModelPart<Pair<List<PredicateDescriptor>, List<BoundaryDescriptor>>>.toModelResolution(): ModelResolution =
    when (this) {
        is ModelPart.Rejected -> ModelResolution.Rejected(NonEmptyList(failures))
        is ModelPart.Resolved -> when (val result = ProofModel.build(value.first, value.second)) {
            is ModelBuildResult.Valid -> ModelResolution.Resolved(result.model)
            is ModelBuildResult.Invalid -> ModelResolution.Rejected(
                NonEmptyList(listOf(ModelResolutionFailure.InvalidModel(result.violations))),
            )
        }
    }
