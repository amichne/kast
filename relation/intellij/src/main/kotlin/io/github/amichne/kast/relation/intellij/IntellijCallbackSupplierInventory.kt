package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierPartition
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

/** Exhaustive reverse supplier closure, finite under recursive forwarding and bounded by the original request. */
internal class IntellijCallbackSupplierInventory(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    private data class Pending(
        val formal: CallbackParameterIdentity,
        val function: KtNamedFunction,
        val parameter: KtParameter,
    )

    private val values = IntellijCallbackSupplierValues(context, summaries)
    private val calls = IntellijCallbackSupplierCalls(context, summaries)
    private val formals = IntellijCallbackSupplierFormal(context)

    fun read(
        root: CallbackParameterIdentity,
        function: KtNamedFunction,
        parameter: KtParameter,
    ): CallbackSupplierInventoryEvidence {
        summaries.findSupplierInventory(root, context.scope.request.scopeFingerprint)?.let {
            return CallbackSupplierInventoryEvidence.Exhaustive(it)
        }
        return when (val result = inventory(root, function, parameter)) {
            is Refinement.Refined ->
                when (val retained = summaries.retainSupplierInventory(result.value)) {
                    is Refinement.Refined -> CallbackSupplierInventoryEvidence.Exhaustive(result.value)
                    is Refinement.Rejected -> unavailable(retained.failure)
                }
            is Refinement.Rejected -> unavailable(result.failure)
        }
    }

    private fun unavailable(cause: CallbackInvocationFlowCause): CallbackSupplierInventoryEvidence.Unavailable {
        summaries.observation.count(IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_REJECTED)
        summaries.observation.terminated(cause.supplierTermination())
        return CallbackSupplierInventoryEvidence.Unavailable(cause)
    }

    private fun inventory(
        root: CallbackParameterIdentity,
        function: KtNamedFunction,
        parameter: KtParameter,
    ): Refinement<CompleteCallbackSupplierInventory, CallbackInvocationFlowCause> {
        when (val retained = summaries.retention.admit(root.retainedBytes + ROOT_RETENTION_BYTES)) {
            is Refinement.Rejected -> return retained
            is Refinement.Refined -> Unit
        }
        return Scan(root, Pending(root, function, parameter)).read()
    }

    private inner class Scan(private val root: CallbackParameterIdentity, initial: Pending) {
        private val scheduled = linkedSetOf(root)
        private val pending = ArrayDeque<Pending>().apply { add(initial) }
        private val partitions = linkedMapOf<CallbackParameterIdentity, CompleteCallbackSupplierPartition>()

        fun read(): Refinement<CompleteCallbackSupplierInventory, CallbackInvocationFlowCause> {
            while (pending.isNotEmpty()) {
                when (val allowed = context.permit()) {
                    is Refinement.Rejected -> return allowed
                    is Refinement.Refined -> Unit
                }
                val current = pending.removeFirst()
                when (val read = partition(current)) {
                    is Refinement.Refined -> partitions[current.formal] = read.value
                    is Refinement.Rejected -> return read
                }
                summaries.observation.count(IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_COMPLETED)
            }
            return when (
                val admitted =
                    CompleteCallbackSupplierInventory.fromCompiler(
                        root,
                        context.scope.request.scopeFingerprint,
                        partitions.values.toList(),
                    )
            ) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected -> unresolved()
            }
        }

        private fun partition(
            current: Pending
        ): Refinement<CompleteCallbackSupplierPartition, CallbackInvocationFlowCause> {
            summaries.observation.count(IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_STARTED)
            val selected =
                when (val scanned = calls.read(current.function, current.parameter, current.formal)) {
                    is Refinement.Refined -> scanned.value
                    is Refinement.Rejected -> return scanned
                }
            val suppliers = mutableListOf<CallbackParameterSupplier>()
            val incoming = mutableListOf<CallbackParameterForwarding>()
            for (call in selected) {
                when (val read = call(call, current.formal)) {
                    is Refinement.Refined ->
                        when (val result = read.value) {
                            is Selection.Values -> suppliers += result.values
                            is Selection.Forwarding -> incoming += result.edge
                        }
                    is Refinement.Rejected -> return read
                }
            }
            return when (
                val admitted =
                    CompleteCallbackSupplierPartition.fromCompiler(
                        current.formal,
                        suppliers,
                        incoming,
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
            ) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected -> unresolved()
            }
        }

        private fun call(
            call: NativeCallbackSupplierCall,
            formal: CallbackParameterIdentity,
        ): Refinement<Selection, CallbackInvocationFlowCause> {
            summaries.observation.count(IntellijReadCounter.CALLBACK_SUPPLIER_CALLS_EXAMINED)
            if (call.binding.invocationOwner !is RelationCallableBody.Named)
                return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            if (call.selection == NativeCallbackSupplierSelection.EXPLICIT) {
                when (val origin = formals.read(call.value, call.lexicalOwner)) {
                    is Refinement.Rejected -> return origin
                    is Refinement.Refined ->
                        when (val upstream = origin.value) {
                            NativeCallbackSupplierFormal.NotFormal -> Unit
                            is NativeCallbackSupplierFormal.Found -> return forward(call, upstream)
                        }
                }
            }
            return when (val read = values.read(call, formal)) {
                is Refinement.Refined -> Refinement.Refined(Selection.Values(read.value))
                is Refinement.Rejected -> read
            }
        }

        private fun forward(
            call: NativeCallbackSupplierCall,
            upstream: NativeCallbackSupplierFormal.Found,
        ): Refinement<Selection.Forwarding, CallbackInvocationFlowCause> {
            val argument = context.occurrence(call.value) ?: return unresolved()
            val edge =
                when (
                    val admitted =
                        CallbackParameterForwarding.fromCompiler(
                            upstream.formal,
                            argument,
                            call.binding,
                            upstream.transfers,
                        )
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return unresolved()
                }
            when (
                val retained =
                    summaries.retention.admit(edge.retainedBytes + upstream.formal.retainedBytes + EDGE_RETENTION_BYTES)
            ) {
                is Refinement.Rejected -> return retained
                is Refinement.Refined -> Unit
            }
            if (scheduled.add(upstream.formal))
                pending.add(Pending(upstream.formal, upstream.function, upstream.parameter))
            return Refinement.Refined(Selection.Forwarding(edge))
        }
    }

    private sealed interface Selection {
        data class Values(val values: List<CallbackParameterSupplier>) : Selection

        data class Forwarding(val edge: CallbackParameterForwarding) : Selection
    }

    private fun unresolved() = Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)

    private companion object {
        const val ROOT_RETENTION_BYTES = 4096L
        const val EDGE_RETENTION_BYTES = 256L
    }
}
