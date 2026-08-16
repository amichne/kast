package io.github.amichne.kast.runtime.server

import io.github.amichne.kast.protocol.contract.CanonicalOperation
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
        is WireRequestAdmission.Admitted ->
            bindingsByOperation.getValue(admission.request.operation).dispatch(admission.request)
    }

    companion object {
        /**
         * Proof transition: `Iterable<TypedOperationBinding<*, *, *, *>> ->
         * RuntimeServerConstruction`.
         *
         * Establishes exactly one captured typed handler binding for every canonical operation.
         * [RuntimeServerConstructionFailure] is the closed expected failure. Binding iteration is
         * permitted only at runtime composition.
         */
        fun create(
            bindings: Iterable<TypedOperationBinding<*, *, *, *>>,
        ): RuntimeServerConstruction {
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
                CanonicalOperation.entries
                    .filterNot(present::contains)
                    .forEach { add(RuntimeServerConstructionFailure.MissingBinding(it)) }
            }
            return if (failures.isEmpty()) {
                RuntimeServerConstruction.Created(
                    RuntimeServer(materialized.associateBy(RuntimeDispatchBinding::operation)),
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
}
