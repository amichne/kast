package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultAllowance
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackOwnerExecution
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.util.IdentityHashMap

/** Proof records share a result grant. Byte estimates are diagnostic, independent of serialized output. */
internal class CallbackFlowRetention(
    private val resultAllowance: ResultAllowance,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    constructor(
        resultLimit: ResultLimit,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ) : this(ResultAllowance.Bounded(resultLimit), observation)

    private var retainedBytes = 0L
    private var retainedResults = 0
    private val formals = CallbackRetainedRecords<CallbackParameterIdentity>(this) { it.retainedBytes }
    private val forwardings = CallbackRetainedRecords<CallbackParameterForwarding>(this) { it.retainedBytes }
    private val invocations = CallbackRetainedRecords<CallbackParameterInvocation>(this) { it.retainedBytes }
    private val owners = CallbackRetainedRecords<CallbackBodyBinding>(this, ::ownerStorageBytes)
    private val values = CallbackRetainedRecords<ImmutableCallbackValue>(this, ::valueStorageBytes)
    private val summaries = CallbackRetainedRecords<CallbackParameterSummary>(this, ::summaryStorageBytes)

    fun admitFormal(formal: CallbackParameterIdentity) = formals.admit(formal)

    fun admitForwarding(forwarding: CallbackParameterForwarding) = forwardings.admit(forwarding)

    fun admitInvocation(invocation: CallbackParameterInvocation) = invocations.admit(invocation)

    fun admitOwner(owner: CallbackBodyBinding) = owners.admit(owner)

    fun admitImmutableValue(value: ImmutableCallbackValue) =
        if (values.owns(value)) admit(SUMMARY_REFERENCE_BYTES) else values.admit(value)

    private fun valueStorageBytes(value: ImmutableCallbackValue): Long {
        if (value.retainedBytes == Long.MAX_VALUE) return value.retainedBytes
        val sites = IdentityHashMap<ValueSite, Unit>()
        return value.valueSites().fold(value.retainedBytes) { bytes, site ->
            val repeated = sites.put(site, Unit) != null
            val owned = values.ownsReference(site) { previous -> previous.valueSites().firstOrNull { it === site } }
            if (repeated || owned) bytes - site.retainedBytes else bytes
        }
    }

    private fun ownerStorageBytes(owner: CallbackBodyBinding): Long {
        if (owner.retainedBytes == Long.MAX_VALUE) return owner.retainedBytes
        return when (val execution = owner.execution) {
            is CallbackOwnerExecution.Observed ->
                execution.flow.ownerBindings.fold(owner.retainedBytes) { bytes, child ->
                    if (owners.owns(child)) bytes - child.execution.retainedBytes else bytes
                } - ownedForwardingBytes(execution.flow.forwarding)
            is CallbackOwnerExecution.Immutable ->
                execution.flow.ownerBindings
                    .fold(owner.retainedBytes) { bytes, child ->
                        if (owners.owns(child)) bytes - child.retainedBytes else bytes
                    }
                    .let { owned ->
                        execution.flow.uses.fold(owned) { bytes, use ->
                            bytes - immutableSharedBytes(use)
                        }
                    }
            CallbackOwnerExecution.Unproven -> owner.retainedBytes
        }
    }

    /** Copies of containers share immutable records only when this grant already owns those exact objects. */
    fun admitSummary(summary: CallbackParameterSummary) = summaries.admit(summary)

    private fun ownedForwardingBytes(evidence: CallbackForwardingEvidence): Long =
        when (evidence) {
            CallbackForwardingEvidence.InvocationRoutes -> 0L
            is CallbackForwardingEvidence.ExhaustedGraph ->
                if (
                    summaries.ownsReference(evidence.graph) {
                        (it.forwarding as? CallbackForwardingEvidence.ExhaustedGraph)?.graph
                    }
                )
                    evidence.graph.retainedBytes
                else 0L
        }

    private fun immutableSharedBytes(use: ImmutableCallbackInvocationUse): Long {
        val valueBytes = if (values.owns(use.value)) use.value.retainedBytes else 0L
        return when (use) {
            is ImmutableCallbackInvocationUse.Direct,
            is ImmutableCallbackInvocationUse.Unused -> valueBytes
            is ImmutableCallbackInvocationUse.Supplied ->
                valueBytes + valueBytes + if (summaries.owns(use.summary)) use.summary.retainedBytes else 0L
        }
    }

    private fun summaryStorageBytes(summary: CallbackParameterSummary): Long =
        when (summaryStorage(summary)) {
            CallbackSummaryStorage.STANDALONE -> summary.retainedBytes
            CallbackSummaryStorage.SHARED_RECORDS ->
                SUMMARY_CONTAINER_BYTES +
                    SUMMARY_REFERENCE_BYTES *
                        (summary.invocations.size + summary.ownerBindings.size + summary.obligations.size)
        }

    private fun summaryStorage(summary: CallbackParameterSummary): CallbackSummaryStorage {
        val graphOwned =
            when (val evidence = summary.forwarding) {
                CallbackForwardingEvidence.InvocationRoutes -> true
                is CallbackForwardingEvidence.ExhaustedGraph ->
                    formals.owns(evidence.graph.root) && evidence.graph.forwardings.all(forwardings::owns)
            }
        val recordsOwned = summary.invocations.all(invocations::owns) && summary.ownerBindings.all(owners::owns)
        return if (formals.owns(summary.formal) && recordsOwned && graphOwned) CallbackSummaryStorage.SHARED_RECORDS
        else CallbackSummaryStorage.STANDALONE
    }

    fun admit(bytes: Long): Refinement<Unit, CallbackInvocationFlowCause> {
        val requiredBytes = if (bytes > Long.MAX_VALUE - retainedBytes) Long.MAX_VALUE else retainedBytes + bytes
        observe(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES, requiredBytes)
        val admitted =
            when {
                !resultAllowance.hasCapacity(retainedResults) -> {
                    observation.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_RESULT_REJECTED)
                    Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED)
                }
                else -> {
                    retainedBytes = requiredBytes
                    retainedResults += 1
                    observation.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_ADMITTED)
                    Refinement.Refined(Unit)
                }
            }
        observe(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES, retainedBytes)
        return admitted
    }

    private fun observe(gauge: IntellijReadGauge, bytes: Long) =
        when (val admitted = IntellijReadGaugeValue.parse(bytes)) {
            is Refinement.Refined -> observation.measure(gauge, admitted.value)
            is Refinement.Rejected -> error("Admitted allowance and conservative callback storage cannot be negative")
        }
}

private const val SUMMARY_CONTAINER_BYTES = 4096L
private const val SUMMARY_REFERENCE_BYTES = 64L

private enum class CallbackSummaryStorage {
    STANDALONE,
    SHARED_RECORDS,
}

private fun ImmutableCallbackValue.valueSites(): Sequence<ValueSite> =
    sequenceOf(source, destination) + transfers.asSequence().flatMap { sequenceOf(it.source, it.target) }
