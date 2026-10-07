package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Every immediate call in the returned anonymous body has one compiler-confirmed disposition. */
sealed interface CallbackFactoryBodyCall {
    val occurrence: RelationOccurrence

    data class Named(override val occurrence: RelationOccurrence, val target: RelationEndpoint.Resolved) :
        CallbackFactoryBodyCall

    data class Captured(val invocation: CallbackParameterInvocation, val formal: CallbackParameterIdentity) :
        CallbackFactoryBodyCall {
        override val occurrence: RelationOccurrence
            get() = invocation.occurrence
    }

    data class Boundary(
        override val occurrence: RelationOccurrence,
        val callable: SourceLessCallable,
        val disposition: SourceLessCallableDisposition,
    ) : CallbackFactoryBodyCall
}

enum class CallbackFactoryBodyFailure {
    INCOMPLETE,
    DUPLICATE_OCCURRENCE,
    OCCURRENCE_OUTSIDE_BODY,
    CAPTURE_OWNER_MISMATCH,
    BOUNDARY_DISPOSITION_MISMATCH,
}

sealed interface CallbackFactoryBodyCalls {
    val retainedBytes: Long

    /** Named and already-returned values carry their invocation proof through their existing origin. */
    data object NotApplicable : CallbackFactoryBodyCalls {
        override val retainedBytes: Long = 0L
    }

    @ConsistentCopyVisibility
    data class Exhaustive
    private constructor(
        val body: RelationCallableBody.Anonymous,
        val calls: List<CallbackFactoryBodyCall>,
    ) : CallbackFactoryBodyCalls {
        override val retainedBytes: Long = calls.fold(4096L) { size, call -> size.addBytes(call.retainedBytes()) }

        companion object {
            fun fromCompiler(
                body: RelationCallableBody.Anonymous,
                calls: List<CallbackFactoryBodyCall>,
                scan: CallbackInvocationScan,
            ): Refinement<Exhaustive, CallbackFactoryBodyFailure> {
                if (scan != CallbackInvocationScan.EXHAUSTIVE)
                    return Refinement.Rejected(CallbackFactoryBodyFailure.INCOMPLETE)
                if (calls.map { it.occurrence }.distinct().size != calls.size)
                    return Refinement.Rejected(CallbackFactoryBodyFailure.DUPLICATE_OCCURRENCE)
                for (call in calls) when (val admitted = call.admitBody(body)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
                return Refinement.Refined(Exhaustive(body, Collections.unmodifiableList(calls.toList())))
            }
        }
    }
}

private fun CallbackFactoryBodyCall.admitBody(
    body: RelationCallableBody.Anonymous
): Refinement<Unit, CallbackFactoryBodyFailure> {
    if (occurrence.file != body.file || !body.range.containsValueRange(occurrence.range))
        return Refinement.Rejected(CallbackFactoryBodyFailure.OCCURRENCE_OUTSIDE_BODY)
    return when (this) {
        is CallbackFactoryBodyCall.Named -> Refinement.Refined(Unit)
        is CallbackFactoryBodyCall.Captured ->
            if (invocation.owner == body) Refinement.Refined(Unit)
            else Refinement.Rejected(CallbackFactoryBodyFailure.CAPTURE_OWNER_MISMATCH)
        is CallbackFactoryBodyCall.Boundary ->
            if (admitsDisposition()) Refinement.Refined(Unit)
            else Refinement.Rejected(CallbackFactoryBodyFailure.BOUNDARY_DISPOSITION_MISMATCH)
    }
}

private fun CallbackFactoryBodyCall.Boundary.admitsDisposition(): Boolean =
    when (callable.moduleKind) {
        SourceLessCallableModuleKind.BUILTINS -> disposition == SourceLessCallableDisposition.BUILTIN_BOUNDARY
        SourceLessCallableModuleKind.LIBRARY -> disposition == SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED
    }

private fun CallbackFactoryBodyCall.retainedBytes(): Long =
    when (this) {
        is CallbackFactoryBodyCall.Named -> 4096L.addBytes(target.detachedTextUnits().multiplyBytes(2))
        is CallbackFactoryBodyCall.Captured -> invocation.retainedBytes.addBytes(formal.retainedBytes)
        is CallbackFactoryBodyCall.Boundary ->
            4096L
                .addBytes(callable.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2))
                .addBytes(callable.moduleName.value.length.toLong().multiplyBytes(2))
    }
