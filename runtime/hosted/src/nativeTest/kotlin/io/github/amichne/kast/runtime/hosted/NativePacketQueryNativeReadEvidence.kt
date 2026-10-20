package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueReviewedCallbackTarget
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.requiredRelationEndpoints
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import java.util.IdentityHashMap

/** Callback entries, model applications, and byte refusals produce separate finite receipts from the actual port. */
internal fun recordPacketNativeRead(request: ValueFlowRequest, read: ValueFlowRead, counters: NativeTraceCounters) {
    val step =
        when (read) {
            is ValueFlowRead.Observed -> read.step
            is ValueFlowRead.Suspended -> read.step
            is ValueFlowRead.Rejected -> {
                recordRejectedRead(request, read)
                if (read.cause == ValueFlowRejection.GRANT_TOO_SMALL) recordByteRejection(counters)
                return
            }
            is ValueFlowRead.ContractRejected -> return
        }
    step.transfers.forEach(::recordCallbackEntry)
    step.applications.forEach(::recordValueModelApplication)
    if (step.obligations.none { it.cause == ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED }) return
    recordByteRejection(counters)
    val source = request.source
    val base =
        ValueFlowStep.detachedByteCount(
                source = source,
                domain = step.domain,
                transfers = emptyList(),
                obligations = emptyList(),
            )
            .value
    println(
        "PACKET_NATIVE_BYTES range=${source.range} role=${source.role::class.simpleName} " +
            "source=${source.retainedBytes} base=$base available=${request.budget.returnedBytes.value} " +
            "response=${step.retainedBytes} pathDepth=${source.valuePath.depth} " +
            "stackDepth=${source.callStack.frames.size} predicates=${source.predicates.entries.size} " +
            "work=${step.examinedWorkUnits.value} transfers=${step.transfers.map { it.kind }} " +
            "causes=${step.obligations.map { it.cause }}"
    )
    source.callStack.frames.forEachIndexed { index, frame ->
        println("PACKET_NATIVE_FRAME index=$index range=${frame.invocation.range} kind=${frame.target.receipt()}")
    }
}

private fun recordValueModelApplication(application: ValueModelApplication) {
    when (application) {
        is ValueModelApplication.CollectionInsertion ->
            println(
                "PACKET_NATIVE_VALUE_MODEL kind=COLLECTION_INSERTION rule=${application.reference.rule.value} " +
                    "source=${application.source.range} target=${application.target.range} " +
                    "invocation=${application.invocation.range} index=${application.index.value} " +
                    "version=${application.binding.artifact.version.value} " +
                    "binarySha256=${application.binding.artifact.binaryDigest.value} " +
                    "sourcePathDepth=${application.source.valuePath.depth} " +
                    "targetPathDepth=${application.target.valuePath.depth} " +
                    "predicates=${application.target.predicates.entries.size}"
            )
        is ValueModelApplication.CollectionSelection ->
            println(
                "PACKET_NATIVE_VALUE_MODEL kind=COLLECTION_SELECTION rule=${application.reference.rule.value} " +
                    "source=${application.source.range} target=${application.target.range} " +
                    "invocation=${application.invocation.range} index=${application.condition.index.value} " +
                    "version=${application.binding.artifact.version.value} " +
                    "binarySha256=${application.binding.artifact.binaryDigest.value} " +
                    "sourcePathDepth=${application.source.valuePath.depth} " +
                    "targetPathDepth=${application.target.valuePath.depth} " +
                    "predicates=${application.target.predicates.entries.size}"
            )
        is ValueModelApplication.ScopeCallbackInput -> recordScopeApplication(application, "SCOPE_CALLBACK_INPUT")
        is ValueModelApplication.ScopeCallbackReturn -> recordScopeApplication(application, "SCOPE_CALLBACK_RETURN")
    }
}

private fun recordScopeApplication(application: ValueModelApplication, kind: String) {
    println(
        "PACKET_NATIVE_VALUE_MODEL kind=$kind rule=${application.reference.rule.value} " +
            "source=${application.source.range} target=${application.target.range} " +
            "invocation=${application.invocation.range} version=${application.binding.artifact.version.value} " +
            "binarySha256=${application.binding.artifact.binaryDigest.value} " +
            "sourcePathDepth=${application.source.valuePath.depth} " +
            "targetPathDepth=${application.target.valuePath.depth} " +
            "sourceFrames=${application.source.callStack.frames.size} " +
            "targetFrames=${application.target.callStack.frames.size} " +
            "predicates=${application.target.predicates.entries.size}"
    )
}

private fun recordRejectedRead(request: ValueFlowRequest, read: ValueFlowRead.Rejected) {
    println(
        "PACKET_NATIVE_READ_REJECTED range=${request.source.range} " +
            "role=${request.source.role::class.simpleName} cause=${read.cause} " +
            "sourceBytes=${request.source.retainedBytes} allowance=${request.budget.returnedBytes.value} " +
            "work=${read.examinedWorkUnits.value} workLimit=${request.budget.resources.workUnitLimit.value} " +
            "consumed=${request.remainder?.consumed?.size ?: 0} emitted=${request.remainder?.emitted?.size ?: 0}"
    )
    request.source.callStack.frames.forEachIndexed(::recordRejectedFrame)
    recordPacketValueProofAllocation(request.source)
}

private fun recordRejectedFrame(index: Int, frame: ValueCallFrame) {
    val callback = frame.target as? ValueCallbackTarget
    val reviewed = frame.target as? ValueReviewedCallbackTarget
    val endpoints = buildList {
        add(frame.invocation.enclosing)
        (frame.invocation as? ValueInvocation)?.let { add(it.callable) }
        add(frame.callee.enclosing)
        if (callback != null) {
            addAll(callback.flow.requiredRelationEndpoints())
            add(callback.supplyingFrame.invocation.enclosing)
            (callback.supplyingFrame.invocation as? ValueInvocation)?.let { call -> add(call.callable) }
            add(callback.supplyingFrame.callee.enclosing)
        }
        if (reviewed != null) {
            add(reviewed.binding.invocation.enclosing)
            add(reviewed.binding.invocation.callable)
            add(reviewed.formal.callable)
            add(reviewed.formal.executionOwner.enclosing)
        }
    }
    val physical = IdentityHashMap<Any, Unit>()
    endpoints.forEach { physical[it] = Unit }
    println(
        "PACKET_NATIVE_REJECTED_FRAME index=$index range=${frame.invocation.range} " +
            "kind=${frame.target.receipt()} flowBytes=${callback?.flow?.retainedBytes ?: 0} " +
            "selectedBytes=${callback?.selected?.retainedBytes ?: 0} " +
            "identityBytes=${callback?.retainedIdentityBytes ?: 0} " +
            "frameEndpointReferences=${endpoints.size} physicalEndpoints=${physical.size} " +
            "fullProofEndpoints=${endpoints.distinct().size}"
    )
}

private fun recordByteRejection(counters: NativeTraceCounters) {
    val candidate = counters[IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_CANDIDATE_BYTES]
    val reserve = counters[IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_OBLIGATION_RESERVE_BYTES]
    val allowance = counters[IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_ALLOWANCE]
    if (candidate == null || reserve == null || allowance == null) {
        println("PACKET_NATIVE_BYTE_REFUSAL operands=UNAVAILABLE")
        return
    }
    println(
        "PACKET_NATIVE_BYTE_REFUSAL candidate=$candidate reserve=$reserve allowance=$allowance"
    )
}

private fun ValueCallTarget.receipt(): String =
    when (this) {
        ValueCallTarget.Direct -> "DIRECT"
        is ValueCallTarget.Implementation -> "IMPLEMENTATION"
        is ValueCallbackTarget -> "CALLBACK:${flow.retainedBytes}"
        is ValueReviewedCallbackTarget -> "REVIEWED_CALLBACK:${binding.model.reference.rule.value}"
    }

private fun recordCallbackEntry(transfer: ValueTransfer) {
    val evidence = (transfer.evidence as? ValueTransferEvidence.Guarded)?.delegate ?: transfer.evidence
    val entry = (evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.Enter ?: return
    val callback = entry.frame.target as? ValueCallbackTarget ?: return
    println(
        "PACKET_NATIVE_CALLBACK selected=${callback.selected.occurrence.range} " +
            "supplier=${callback.supplyingFrame.invocation.range} target=${transfer.target.range} " +
            "targetOwner=${transfer.target.executionOwner.body::class.simpleName} " +
            "predicates=${transfer.target.predicates.entries.size}"
    )
}
