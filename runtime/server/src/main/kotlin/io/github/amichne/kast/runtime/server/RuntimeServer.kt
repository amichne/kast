package io.github.amichne.kast.runtime.server

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import io.github.amichne.kast.protocol.registry.HostedExposure
import io.github.amichne.kast.protocol.registry.OperationDefinition
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope

/**
 * Contract-only request-frame server for the canonical wire protocol.
 *
 * A UDS host supplies complete request documents to [dispatch]; physical socket acceptance and
 * framing remain explicit host effects rather than dependencies of typed dispatch.
 */
class RuntimeServer private constructor(
    private val bindingsByOperation: Map<CanonicalOperation, RuntimeDispatchBinding>,
) {
    /**
     * Proof transition: `String -> ServerDispatch`.
     *
     * Establishes request-envelope admission, exact canonical route selection, generated request
     * decoding, typed handler execution, and canonical outcome encoding. [ServerDispatchFailure]
     * is the closed expected failure. Raw request and response documents are permitted only at the
     * outer UDS frame boundary.
     */
    suspend fun dispatch(document: String): ServerDispatch = when (
        val admission = WireRequestEnvelope.admit(document)
    ) {
        is WireRequestAdmission.Rejected -> ServerDispatch.Rejected(
            ServerDispatchFailure.RequestAdmissionFailed(admission.failure),
        )
        is WireRequestAdmission.Admitted -> bindingsByOperation[admission.request.operation]
            ?.dispatch(admission.request)
            ?: ServerDispatch.Rejected(
                ServerDispatchFailure.UnsupportedOperation(admission.request.operation),
            )
    }

    companion object {
        /**
         * Proof transition: `Iterable<TypedOperationBinding<*, *, *, *>> ->
         * RuntimeServerConstruction`.
         *
         * Establishes exactly one typed handler binding for every available canonical operation,
         * including internal services, then retains only public definitions as wire routes.
         * [RuntimeServerConstructionFailure] is the closed expected failure. Binding iteration is
         * permitted only at runtime composition.
         */
        fun create(
            bindings: Iterable<TypedOperationBinding<*, *, *, *>>,
        ): RuntimeServerConstruction = createFromDefinitions(bindings, CanonicalOperationDefinitions.all)

        /** Definition-based seam for exercising closed exposure states absent from today's registry. */
        internal fun createFromDefinitions(
            bindings: Iterable<TypedOperationBinding<*, *, *, *>>,
            definitions: List<OperationDefinition<*, *, *, *, *>>,
        ): RuntimeServerConstruction {
            val availableDefinitions = definitions.filter { definition ->
                when (definition.hostedExposure) {
                    HostedExposure.PUBLIC, HostedExposure.INTERNAL_ONLY -> true
                    HostedExposure.UNAVAILABLE -> false
                }
            }
            val requiredOperations = availableDefinitions.mapTo(linkedSetOf()) { it.operation }
            val publicOperations = availableDefinitions
                .filter { it.hostedExposure == HostedExposure.PUBLIC }
                .mapTo(linkedSetOf()) { it.operation }
            val materialized = bindings.map(TypedOperationBinding<*, *, *, *>::dispatchBinding)
            val failures = buildSet {
                materialized
                    .groupingBy(RuntimeDispatchBinding::operation)
                    .eachCount()
                    .filterValues { count -> count > 1 }
                    .keys
                    .sortedBy(CanonicalOperation::ordinal)
                    .forEach { add(RuntimeServerConstructionFailure.DuplicateBinding(it)) }

                val present = materialized.mapTo(mutableSetOf(), RuntimeDispatchBinding::operation)
                requiredOperations
                    .filterNot(present::contains)
                    .sortedBy(CanonicalOperation::ordinal)
                    .forEach { add(RuntimeServerConstructionFailure.MissingBinding(it)) }
                present
                    .filterNot(requiredOperations::contains)
                    .sortedBy(CanonicalOperation::ordinal)
                    .forEach { add(RuntimeServerConstructionFailure.UnexpectedBinding(it)) }
            }
            return if (failures.isEmpty()) {
                RuntimeServerConstruction.Created(
                    RuntimeServer(
                        materialized.filter { it.operation in publicOperations }
                            .associateBy(RuntimeDispatchBinding::operation),
                    ),
                )
            } else {
                RuntimeServerConstruction.Rejected(failures)
            }
        }
    }
}

/** Closed construction result for the exact runtime binding table. */
sealed interface RuntimeServerConstruction {
    data class Created(
        val server: RuntimeServer,
    ) : RuntimeServerConstruction

    data class Rejected(
        val failures: Set<RuntimeServerConstructionFailure>,
    ) : RuntimeServerConstruction
}

/** Closed exact-table construction failures. */
sealed interface RuntimeServerConstructionFailure {
    data class MissingBinding(
        val operation: CanonicalOperation,
    ) : RuntimeServerConstructionFailure

    data class DuplicateBinding(
        val operation: CanonicalOperation,
    ) : RuntimeServerConstructionFailure

    data class UnexpectedBinding(
        val operation: CanonicalOperation,
    ) : RuntimeServerConstructionFailure
}
