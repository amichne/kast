package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** Closed IDE-host publication status owned by one canonical operation definition. */
enum class HostedExposure {
    PUBLIC,
    INTERNAL_ONLY,
    UNAVAILABLE,
}

/** The only mutation intent admitted by the first hosted writer slice. */
enum class HostedChangeIntent(val identity: String) {
    ADD_DECLARATION("add-declaration"),
}

/** Closed variant metadata for an operation's hosted surface. */
sealed interface HostedVariants {
    data object None : HostedVariants

    data class Intents(
        val intents: Set<HostedChangeIntent>,
    ) : HostedVariants
}

sealed interface HostedBindingCompleteness {
    data object Complete : HostedBindingCompleteness

    data class Rejected(
        val failures: Set<HostedBindingCompletenessFailure>,
    ) : HostedBindingCompleteness
}

sealed interface HostedBindingCompletenessFailure {
    data class MissingPublicBinding(
        val operation: CanonicalOperation,
    ) : HostedBindingCompletenessFailure

    data class DuplicatePublicBinding(
        val operation: CanonicalOperation,
    ) : HostedBindingCompletenessFailure

    data class NonPublicBinding(
        val operation: CanonicalOperation,
        val exposure: HostedExposure,
    ) : HostedBindingCompletenessFailure
}

/** Deterministic hosted surface projected only from [CanonicalOperationDefinitions]. */
object HostedOperationProjection {
    val publicDefinitions: List<OperationDefinition<*, *, *, *, *>> =
        definitions(HostedExposure.PUBLIC)

    val internalDefinitions: List<OperationDefinition<*, *, *, *, *>> =
        definitions(HostedExposure.INTERNAL_ONLY)

    val unavailableDefinitions: List<OperationDefinition<*, *, *, *, *>> =
        definitions(HostedExposure.UNAVAILABLE)

    /**
     * Proves exactly one route binding for each public operation and no route binding for a
     * non-public operation. Internal services remain composition inputs rather than wire routes.
     */
    fun verifyBindings(bindings: Iterable<CanonicalOperation>): HostedBindingCompleteness {
        val materialized = bindings.toList()
        val counts = materialized.groupingBy { it }.eachCount()
        val exposureByOperation = CanonicalOperationDefinitions.all.associate {
            it.operation to it.hostedExposure
        }
        val failures = buildSet {
            publicDefinitions
                .map { it.operation }
                .filterNot(counts::containsKey)
                .forEach { add(HostedBindingCompletenessFailure.MissingPublicBinding(it)) }
            counts
                .filterValues { it > 1 }
                .keys
                .sortedBy(CanonicalOperation::ordinal)
                .forEach { add(HostedBindingCompletenessFailure.DuplicatePublicBinding(it)) }
            counts.keys
                .filter { exposureByOperation.getValue(it) != HostedExposure.PUBLIC }
                .sortedBy(CanonicalOperation::ordinal)
                .forEach {
                    add(
                        HostedBindingCompletenessFailure.NonPublicBinding(
                            it,
                            exposureByOperation.getValue(it),
                        ),
                    )
                }
        }
        return if (failures.isEmpty()) {
            HostedBindingCompleteness.Complete
        } else {
            HostedBindingCompleteness.Rejected(failures)
        }
    }

    private fun definitions(
        exposure: HostedExposure,
    ): List<OperationDefinition<*, *, *, *, *>> =
        CanonicalOperationDefinitions.all.filter { it.hostedExposure == exposure }
}
