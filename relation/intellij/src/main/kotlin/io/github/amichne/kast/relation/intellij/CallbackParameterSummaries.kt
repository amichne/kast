package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Request-owned detached evidence. The exact formal retains its semantic basis; no PSI enters storage. */
internal class CallbackParameterSummaries(
    budget: RelationBudget,
    val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val cache: io.github.amichne.kast.relation.contract.CallbackSummaryCachePort =
        io.github.amichne.kast.relation.contract.CallbackSummaryCachePort.Disabled,
    private val supplierReadmit:
        (
            io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
        ) -> io.github.amichne.kast.relation.contract.CallbackReadmission<
                io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
            > =
        { previous ->
            Refinement.Rejected(
                io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure.MissingEndpoint(
                    previous.root.callable.valueIdentity
                )
            )
        },
    private val readmit:
        (CallbackParameterSummary) -> io.github.amichne.kast.relation.contract.CallbackReadmission<
                CallbackParameterSummary
            > =
        { previous ->
            Refinement.Rejected(
                io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure.MissingEndpoint(
                    previous.formal.callable.valueIdentity
                )
            )
        },
) {
    val retention = CallbackFlowRetention(budget)
    private val summaries = linkedMapOf<CallbackParameterIdentity, CallbackParameterSummary>()

    private val pendingProjectUse = mutableSetOf<CallbackParameterIdentity>()
    private val supplierInventories =
        linkedMapOf<
            Pair<CallbackParameterIdentity, io.github.amichne.kast.relation.contract.RelationScopeFingerprint>,
            io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory,
        >()
    private val pendingSupplierUse =
        mutableSetOf<
            Pair<CallbackParameterIdentity, io.github.amichne.kast.relation.contract.RelationScopeFingerprint>
        >()

    fun findSupplierInventory(
        root: CallbackParameterIdentity,
        domain: io.github.amichne.kast.relation.contract.RelationScopeFingerprint,
    ): io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory? {
        val key = root to domain
        supplierInventories[key]?.let {
            return it
        }
        return when (val cached = cache.suppliers.find(root, domain, supplierReadmit)) {
            io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup.Miss -> null
            is io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup.Found ->
                when (retention.admit(cached.inventory.retainedBytes)) {
                    is Refinement.Refined ->
                        cached.inventory.also {
                            supplierInventories[key] = it
                            pendingSupplierUse += key
                        }
                    is Refinement.Rejected -> null
                }
        }
    }

    fun retainSupplierInventory(
        inventory: io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
    ): Refinement<Unit, CallbackInvocationFlowCause> {
        val key = inventory.root to inventory.domain
        if (key in supplierInventories) return Refinement.Refined(Unit)
        return when (val admitted = retention.admit(inventory.retainedBytes)) {
            is Refinement.Refined -> {
                supplierInventories[key] = inventory
                cache.suppliers.retain(inventory)
                admitted
            }
            is Refinement.Rejected -> admitted
        }
    }

    fun supplierUsed(inventory: io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory) {
        if (pendingSupplierUse.remove(inventory.root to inventory.domain)) cache.suppliers.admitted(inventory)
    }

    fun used(summary: CallbackParameterSummary) {
        if (pendingProjectUse.remove(summary.formal)) cache.admitted(summary)
    }

    fun find(formal: CallbackParameterIdentity): CallbackParameterSummary? {
        summaries[formal]?.let {
            return it
        }
        return when (val cached = cache.find(formal, readmit)) {
            io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup.Miss -> null
            is io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup.Found ->
                when (retention.admitSummary(cached.summary)) {
                    is Refinement.Refined ->
                        cached.summary.also {
                            summaries[formal] = it
                            pendingProjectUse += formal
                        }
                    is Refinement.Rejected -> {
                        observation.count(
                            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                                .CALLBACK_SUMMARY_RETENTION_REJECTIONS
                        )
                        null
                    }
                }
        }
    }

    fun retain(summary: CallbackParameterSummary): Refinement<Unit, CallbackInvocationFlowCause> {
        if (summary.formal in summaries) return Refinement.Refined(Unit)
        return when (val admitted = retention.admitSummary(summary)) {
            is Refinement.Refined -> {
                summaries[summary.formal] = summary
                cache.retain(summary)
                admitted
            }
            is Refinement.Rejected -> admitted
        }
    }
}
