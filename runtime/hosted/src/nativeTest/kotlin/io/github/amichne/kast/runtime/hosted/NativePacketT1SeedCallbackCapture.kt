package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Captures an actual query read, then asks the existing relation port for an independent fresh callback proof. */
internal class NativePacketT1SeedCallbackCapture(private val observation: NativeTraceCounters) {
    private var before: Map<IntellijReadCounter, Long>? = null
    private val returns = mutableListOf<Pair<ValueFlowRequest, ValueFlowRead>>()

    fun beforeRead(request: ValueFlowRequest) {
        if (isSelected(request)) before = observation.snapshot()
    }

    fun record(request: ValueFlowRequest, read: ValueFlowRead) {
        if (!isSelected(request)) return
        val prior = checkNotNull(before) { "The exact Return needs a pre-read counter snapshot" }
        fun delta(counter: IntellijReadCounter) = observation[counter] - (prior[counter] ?: 0L)
        println(
            "T1_RETURN_PRIVATE_COMPARISON " +
                "matched=${delta(IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_MATCHED)} " +
                "mismatch=${delta(IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_MISMATCH)} " +
                "scopeMismatch=${delta(IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_SCOPE_MISMATCH)} " +
                "constraintsMismatch=" +
                delta(IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_CONSTRAINTS_MISMATCH)
        )
        returns += request to read
    }

    private fun isSelected(request: ValueFlowRequest): Boolean {
        val source = request.source
        if (source.role != ValueRole.Return || source.range != resultRange()) return false
        val body = source.executionOwner.body as? ValueExecutionBody.Anonymous ?: return false
        return body.callable.range == bodyRange()
    }

    fun returned(): Pair<ValueFlowRequest, ValueFlowRead> =
        returns.singleOrNull() ?: error("Original T1 needs one actual Return1029:1057 read, found ${returns.size}")

    suspend fun callback(services: NativePacketQueryServices, original: ValueFlowRequest): CallbackInvocationFlow {
        val question =
            RelationRequest.start(
                subject = original.source.enclosing,
                meaning = RelationMeaning.Callees,
                budget = original.budget,
                boundary = original.boundary,
            )
        val batch =
            when (val read = services.readRelations(question)) {
                is RelationReadResult.Complete -> {
                    println("T1_RETURN_CALLEES coverage=COMPLETE count=${read.coverage.exactCount.value}")
                    read.batch
                }
                is RelationReadResult.Qualified -> {
                    println("T1_RETURN_CALLEES coverage=QUALIFIED causes=${read.coverage.limitations}")
                    read.batch
                }
                is RelationReadResult.Rejected -> error("T1 callback proof prerequisite rejected: ${read.reason}")
            }
        val candidates = batch.callbackObservations.filter { it.callbackBody.range == bodyRange() }
        val flows = candidates.mapNotNull { (it.flow as? CallbackInvocationFlowRead.Observed)?.flow }
        val bound = flows.filter { it.binding is CallbackBindingEvidence.Bound }
        check(bound.isNotEmpty()) {
            "Actual original sequential callback binding unavailable: " +
                "reads=${candidates.map { it.flow::class.simpleName }}"
        }
        val invocations = bound.map { (it.binding as CallbackBindingEvidence.Bound).binding.invocation }.distinct()
        check(invocations.size == 1) { "Actual original callback has ${invocations.size} distinct supplying proofs" }
        return bound.first().also { flow ->
            println(
                "T1_RETURN_CALLBACK scan=${flow.scan} causes=${flow.obligations} invocations=${flow.invocations.size}"
            )
        }
    }

    private fun resultRange() = exact(RESULT_START, RESULT_END)

    private fun bodyRange() = exact(BODY_START, BODY_END)

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).packetQueryRefined()

    private companion object {
        const val RESULT_START = 1029
        const val RESULT_END = 1057
        const val BODY_START = 677
        const val BODY_END = 1063
    }
}
