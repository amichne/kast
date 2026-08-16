package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.runtime.server.RuntimeServer
import io.github.amichne.kast.runtime.server.RuntimeServerConstruction
import io.github.amichne.kast.runtime.server.RuntimeServerConstructionFailure
import io.github.amichne.kast.runtime.server.ServerDispatch
import io.github.amichne.kast.runtime.server.TypedOperationBinding

/** Runnable target-only runtime containing the direct operation graph and its typed server. */
class KastRuntimeComposition private constructor(
    val operations: DirectKastOperations,
    private val server: RuntimeServer,
) : KastRuntimeDispatchOperations {
    /**
     * Proof transition: `String -> KastRuntimeDispatch`.
     *
     * Preserves canonical wire admission and exact target operation routing. Closed request,
     * decoding, semantic, and response failures remain data. Raw documents may cross only this
     * outer indexer transport boundary.
     */
    override suspend fun dispatch(document: String): KastRuntimeDispatch = when (
        val dispatch = server.dispatch(document)
    ) {
        is ServerDispatch.Responded -> KastRuntimeDispatch.Responded(dispatch.document)
        is ServerDispatch.Rejected -> KastRuntimeDispatch.Rejected(
            when (dispatch.failure) {
                is io.github.amichne.kast.runtime.server.ServerDispatchFailure.RequestAdmissionFailed ->
                    KastRuntimeDispatchFailure.REQUEST_ADMISSION_FAILED
                is io.github.amichne.kast.runtime.server.ServerDispatchFailure.RequestDecodingFailed ->
                    KastRuntimeDispatchFailure.REQUEST_DECODING_FAILED
                is io.github.amichne.kast.runtime.server.ServerDispatchFailure.ResponseEncodingFailed ->
                    KastRuntimeDispatchFailure.RESPONSE_ENCODING_FAILED
            },
        )
    }

    companion object {
        /**
         * Proof transition: `(KastRuntimeServices, KastOperationBindingFactory) ->
         * KastRuntimeCompositionConstruction`.
         *
         * Establishes the direct target service graph, exact nominal operation-to-binding
         * association, and one complete runtime dispatch table. [KastRuntimeCompositionFailure]
         * is the closed expected failure. Generated serialization is permitted only through the
         * binding factory boundary.
         */
        fun create(
            services: KastRuntimeServices,
            bindings: KastOperationBindingFactory,
        ): KastRuntimeCompositionConstruction {
            val operations = DirectKastOperations.from(services)
            val named = listOf(
                NamedOperationBinding(
                    CanonicalOperation.WORKSPACE_INSPECT,
                    bindings.workspaceInspect(operations.workspaceInspect),
                ),
                NamedOperationBinding(
                    CanonicalOperation.SYMBOL_DISCOVER,
                    bindings.symbolDiscover(operations.symbolDiscover),
                ),
                NamedOperationBinding(
                    CanonicalOperation.SYMBOL_RESOLVE,
                    bindings.symbolResolve(operations.symbolResolve),
                ),
                NamedOperationBinding(
                    CanonicalOperation.SYMBOL_DESCRIBE,
                    bindings.symbolDescribe(operations.symbolDescribe),
                ),
                NamedOperationBinding(
                    CanonicalOperation.RELATION_READ,
                    bindings.relationRead(operations.relationRead),
                ),
                NamedOperationBinding(
                    CanonicalOperation.TRAVERSAL_RUN,
                    bindings.traversalRun(operations.traversalRun),
                ),
                NamedOperationBinding(
                    CanonicalOperation.DIAGNOSTIC_CHECK,
                    bindings.diagnosticCheck(operations.diagnosticCheck),
                ),
                NamedOperationBinding(
                    CanonicalOperation.CHANGE_PLAN,
                    bindings.changePlan(operations.changePlan),
                ),
                NamedOperationBinding(
                    CanonicalOperation.CHANGE_APPLY,
                    bindings.changeApply(operations.changeApply),
                ),
                NamedOperationBinding(
                    CanonicalOperation.CHANGE_VERIFY,
                    bindings.changeVerify(operations.changeVerify),
                ),
                NamedOperationBinding(
                    CanonicalOperation.CHANGE_RECOVER,
                    bindings.changeRecover(operations.changeRecover),
                ),
            )
            val mismatches = named
                .filter { it.expected != it.binding.operation }
                .mapTo(linkedSetOf()) {
                    KastRuntimeCompositionFailure.BindingOperationMismatch(
                        expected = it.expected,
                        observed = it.binding.operation,
                    )
                }
            if (mismatches.isNotEmpty()) {
                return KastRuntimeCompositionConstruction.Rejected(mismatches)
            }
            return when (val construction = RuntimeServer.create(named.map { it.binding })) {
                is RuntimeServerConstruction.Created -> KastRuntimeCompositionConstruction.Created(
                    KastRuntimeComposition(operations, construction.server),
                )
                is RuntimeServerConstruction.Rejected -> KastRuntimeCompositionConstruction.Rejected(
                    construction.failures.mapTo(linkedSetOf()) {
                        KastRuntimeCompositionFailure.ServerConstruction(it)
                    },
                )
            }
        }
    }
}

/** Narrow composition-owned dispatch capability supplied to the isolated indexer host. */
fun interface KastRuntimeDispatchOperations {
    /**
     * Proof transition: `String -> KastRuntimeDispatch`.
     *
     * Establishes canonical request admission and exact operation routing. Expected failures are
     * closed by [KastRuntimeDispatch.Rejected]. Raw documents may cross only the outer host frame.
     */
    suspend fun dispatch(document: String): KastRuntimeDispatch
}

/** Composition-owned dispatch result exposed to the isolated indexer host. */
sealed interface KastRuntimeDispatch {
    data class Responded(
        val document: String,
    ) : KastRuntimeDispatch

    data class Rejected(
        val failure: KastRuntimeDispatchFailure,
    ) : KastRuntimeDispatch
}

/** Closed transport failure projection that does not export runtime-server implementation types. */
enum class KastRuntimeDispatchFailure {
    REQUEST_ADMISSION_FAILED,
    REQUEST_DECODING_FAILED,
    RESPONSE_ENCODING_FAILED,
}

private data class NamedOperationBinding(
    val expected: CanonicalOperation,
    val binding: TypedOperationBinding<*, *, *, *>,
)

/** Closed construction result for the target-only runtime graph. */
sealed interface KastRuntimeCompositionConstruction {
    data class Created(
        val composition: KastRuntimeComposition,
    ) : KastRuntimeCompositionConstruction

    data class Rejected(
        val failures: Set<KastRuntimeCompositionFailure>,
    ) : KastRuntimeCompositionConstruction
}

/** Finite failures that prevent target runtime authority from being issued. */
sealed interface KastRuntimeCompositionFailure {
    data class BindingOperationMismatch(
        val expected: CanonicalOperation,
        val observed: CanonicalOperation,
    ) : KastRuntimeCompositionFailure

    data class ServerConstruction(
        val failure: RuntimeServerConstructionFailure,
    ) : KastRuntimeCompositionFailure
}
