package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import java.nio.charset.StandardCharsets

/** Native page counters are supplied before detached evidence can mutate the request-local buffer. */
internal data class IntellijRelationEvidenceAllowance(
    val facts: List<RelationFact>,
    val references: List<RelationReferenceOccurrence>,
    val retainedBytes: Long,
) {
    val semanticResults: Int
        get() =
            references.size +
                facts.count { fact ->
                    references.none { it.occurrence == fact.occurrence && it.target == fact.target }
                }
}

internal sealed interface IntellijRelationEvidenceAdmission {
    data object ContractRejected : IntellijRelationEvidenceAdmission

    data object Duplicate : IntellijRelationEvidenceAdmission

    data class Limited(val limitation: RelationLimitation) : IntellijRelationEvidenceAdmission

    /** Exact canonical UTF-8 bytes measured at the native buffer boundary. */
    data class Recorded(val encodedBytes: RelationByteCount) : IntellijRelationEvidenceAdmission
}

/** Owns only detached observation retention; provider consumption and instrumentation stay in the collector. */
internal class IntellijRelationEvidenceBuffer(private val request: RelationRequest) {
    private val exclusions = mutableListOf<RelationScopeExclusion>()
    private val callbacks = mutableListOf<RelationCallbackObservation>()
    private val callables = mutableListOf<RelationCallableObservation>()

    val scopeExclusions: List<RelationScopeExclusion>
        get() = exclusions

    val callbackObservations: List<RelationCallbackObservation>
        get() = callbacks

    val callableObservations: List<RelationCallableObservation>
        get() = callables

    fun resultCount(allowance: IntellijRelationEvidenceAllowance): Int =
        allowance.semanticResults +
            exclusions.size +
            callables.size +
            callbacks.count { callback ->
                allowance.facts.none(callback::supportsNamedFact)
            }

    /** The owning collector has already proved this callback supports its accepted named fact. */
    fun retainNamedCallback(value: RelationCallbackObservation) {
        callbacks += value
    }

    fun accept(
        value: RelationScopeExclusion,
        allowance: IntellijRelationEvidenceAllowance,
    ): IntellijRelationEvidenceAdmission =
        if (!value.belongsTo(request)) IntellijRelationEvidenceAdmission.ContractRejected
        else retain(value, exclusions, value::canonicalProjection, allowance)

    fun accept(
        value: RelationCallbackObservation,
        allowance: IntellijRelationEvidenceAllowance,
    ): IntellijRelationEvidenceAdmission =
        if (!value.belongsTo(request)) IntellijRelationEvidenceAdmission.ContractRejected
        else retain(value, callbacks, value::canonicalProjection, allowance)

    fun accept(
        value: RelationCallableObservation,
        allowance: IntellijRelationEvidenceAllowance,
    ): IntellijRelationEvidenceAdmission =
        if (!value.belongsTo(request)) IntellijRelationEvidenceAdmission.ContractRejected
        else retain(value, callables, value::canonicalProjection, allowance)

    private fun <Value> retain(
        value: Value,
        values: MutableList<Value>,
        canonicalProjection: () -> String,
        allowance: IntellijRelationEvidenceAllowance,
    ): IntellijRelationEvidenceAdmission {
        if (value in values) return IntellijRelationEvidenceAdmission.Duplicate
        if (resultCount(allowance) >= request.budget.resources.resultLimit.value)
            return IntellijRelationEvidenceAdmission.Limited(RelationLimitation.RESULT_LIMIT_REACHED)
        val bytes = canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (allowance.retainedBytes + bytes > request.budget.returnedBytes.value)
            return IntellijRelationEvidenceAdmission.Limited(RelationLimitation.BYTE_LIMIT_REACHED)
        val measured =
            when (val refined = RelationByteCount.parse(bytes)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return IntellijRelationEvidenceAdmission.ContractRejected
            }
        values += value
        return IntellijRelationEvidenceAdmission.Recorded(measured)
    }
}
