package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class RepresentationUnknownReason {
    UNMODELED_TRANSFORMATION,
    INPUT_STATE_NOT_ESTABLISHED,
    BOUNDARY_PRESERVATION_UNPROVEN,
}

sealed interface RepresentationCurrent {
    data class Known(val state: RepresentationState) : RepresentationCurrent

    data class Unknown(val reason: RepresentationUnknownReason) : RepresentationCurrent
}

/** Model history and compiler flow history remain separate alternatives. */
sealed interface RepresentationHistory {
    sealed interface Model : RepresentationHistory {
        val reference: ModelRuleReference
        val site: ValueSiteIdentity
    }

    @ConsistentCopyVisibility
    data class Origin
    internal constructor(val rule: RepresentationRule.Origin, val output: ValueSite, val invocation: ValueInvocation) :
        Model {
        override val reference: ModelRuleReference
            get() = rule.reference

        override val site: ValueSiteIdentity
            get() = output.identity
    }

    data class CompilerTransfer(val transfer: ValueTransfer) : RepresentationHistory

    data class Unmodeled(val source: ValueSiteIdentity, val target: ValueSiteIdentity) : RepresentationHistory

    data class BoundaryModel(
        val reference: ModelRuleReference,
        val source: BoundaryPositionReference,
        val target: BoundaryPositionReference,
    ) : RepresentationHistory
}

/** Guarded application of an admitted rule to one exact invocation, separate from compiler-flow proof. */
sealed interface RepresentationModelApplication : RepresentationHistory.Model {
    val source: ValueSite
    val target: ValueSite
    val invocation: ValueInvocation
    override val site: ValueSiteIdentity
        get() = target.identity

    @ConsistentCopyVisibility
    data class Transfer
    internal constructor(
        override val source: ValueSite,
        override val target: ValueSite,
        override val invocation: ValueInvocation,
        val rule: RepresentationRule.Transfer,
    ) : RepresentationModelApplication {
        override val reference: ModelRuleReference
            get() = rule.reference
    }

    @ConsistentCopyVisibility
    data class Transformation
    internal constructor(
        override val source: ValueSite,
        override val target: ValueSite,
        override val invocation: ValueInvocation,
        val rule: RepresentationRule.Transformation,
    ) : RepresentationModelApplication {
        override val reference: ModelRuleReference
            get() = rule.reference
    }
}

@ConsistentCopyVisibility
data class RepresentationBranch
internal constructor(val current: RepresentationCurrent, val history: List<RepresentationHistory>)

enum class RepresentationPropagationFailure {
    SITE_MISMATCH,
    BASIS_MISMATCH,
    CALLABLE_MISMATCH,
    POSITION_MISMATCH,
    EMPTY_MERGE,
}

sealed interface ConsumerRepresentationEvidence {
    val rule: RepresentationRule.ConsumerExpectation
    val reference: ModelRuleReference
        get() = rule.reference

    @ConsistentCopyVisibility
    data class Satisfied
    internal constructor(
        override val rule: RepresentationRule.ConsumerExpectation,
        val evidence: RepresentationEvidence,
    ) : ConsumerRepresentationEvidence

    @ConsistentCopyVisibility
    data class Different
    internal constructor(
        override val rule: RepresentationRule.ConsumerExpectation,
        val evidence: RepresentationEvidence,
    ) : ConsumerRepresentationEvidence

    @ConsistentCopyVisibility
    data class Unknown
    internal constructor(
        override val rule: RepresentationRule.ConsumerExpectation,
        val evidence: RepresentationEvidence,
    ) : ConsumerRepresentationEvidence
}

/** Immutable alternatives associate each current state with exactly its own provenance history. */
class RepresentationEvidence private constructor(val site: ValueSite, val branches: Set<RepresentationBranch>) {
    override fun equals(other: Any?): Boolean =
        other is RepresentationEvidence && site == other.site && branches == other.branches

    override fun hashCode(): Int = 31 * site.hashCode() + branches.hashCode()

    fun throughBoundary(
        connection: BoundaryArrival.Connected
    ): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
        if (site.identity != connection.source.site.identity)
            return Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH)
        val preserves = BoundaryCompatibilityAssumption.REPRESENTATION_PRESERVED in connection.model.assumptions
        return Refinement.Refined(
            create(
                connection.target.site,
                branches.map {
                    it.withHistory(
                        RepresentationHistory.BoundaryModel(
                            connection.model.reference,
                            connection.source.reference,
                            connection.target.reference,
                        ),
                        if (preserves) it.current
                        else RepresentationCurrent.Unknown(RepresentationUnknownReason.BOUNDARY_PRESERVATION_UNPROVEN),
                    )
                },
            )
        )
    }

    fun transfer(transfer: ValueTransfer): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
        if (site.identity != transfer.source.identity)
            return Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH)
        return Refinement.Refined(
            create(transfer.target, branches.map { it.withHistory(RepresentationHistory.CompilerTransfer(transfer)) })
        )
    }

    /** This is a modeled edge, not a newly manufactured compiler-flow edge. */
    fun transform(
        output: ValueSite,
        invocation: ValueInvocation,
        rule: RepresentationRule.Transformation,
    ): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
        when (val admission = admitModelCall(site, output, invocation, rule.input, rule.output)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admission
        }
        return Refinement.Refined(
            create(
                output,
                branches.map { branch ->
                    val next =
                        when (val current = branch.current) {
                            is RepresentationCurrent.Known ->
                                if (current.state == rule.from) RepresentationCurrent.Known(rule.to)
                                else
                                    RepresentationCurrent.Unknown(
                                        RepresentationUnknownReason.INPUT_STATE_NOT_ESTABLISHED
                                    )
                            is RepresentationCurrent.Unknown -> current
                        }
                    branch.withHistory(
                        RepresentationModelApplication.Transformation(site, output, invocation, rule),
                        next,
                    )
                },
            )
        )
    }

    fun modeledTransfer(
        output: ValueSite,
        invocation: ValueInvocation,
        rule: RepresentationRule.Transfer,
    ): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
        when (val admission = admitModelCall(site, output, invocation, rule.input, rule.output)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admission
        }
        return Refinement.Refined(
            create(
                output,
                branches.map {
                    it.withHistory(RepresentationModelApplication.Transfer(site, output, invocation, rule))
                },
            )
        )
    }

    /** Unsupported transformation preserves history while weakening every known current-state claim. */
    fun unmodeled(output: ValueSite): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
        if (site.basis != output.basis) return Refinement.Rejected(RepresentationPropagationFailure.BASIS_MISMATCH)
        return Refinement.Refined(
            create(
                output,
                branches.map {
                    it.withHistory(
                        RepresentationHistory.Unmodeled(site.identity, output.identity),
                        RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
                    )
                },
            )
        )
    }

    fun expect(
        rule: RepresentationRule.ConsumerExpectation
    ): Refinement<ConsumerRepresentationEvidence, RepresentationPropagationFailure> {
        when (val admission = admitInputPosition(site, rule.input)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admission
        }
        if (branches.any { it.current is RepresentationCurrent.Unknown })
            return Refinement.Refined(ConsumerRepresentationEvidence.Unknown(rule, this))
        if (branches.all { it.current == RepresentationCurrent.Known(rule.expected) })
            return Refinement.Refined(ConsumerRepresentationEvidence.Satisfied(rule, this))
        return Refinement.Refined(ConsumerRepresentationEvidence.Different(rule, this))
    }

    companion object {
        fun origin(
            site: ValueSite,
            invocation: ValueInvocation,
            rule: RepresentationRule.Origin,
        ): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
            when (val admission = admitOutputPosition(site, invocation, rule.output)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admission
            }
            return Refinement.Refined(
                create(
                    site,
                    listOf(
                        RepresentationBranch(
                            RepresentationCurrent.Known(rule.state),
                            listOf(RepresentationHistory.Origin(rule, site, invocation)),
                        )
                    ),
                )
            )
        }

        /** Incoming compiler edges must establish arrival at the same exact branch merge site first. */
        fun merge(
            incoming: List<RepresentationEvidence>
        ): Refinement<RepresentationEvidence, RepresentationPropagationFailure> {
            if (incoming.isEmpty()) return Refinement.Rejected(RepresentationPropagationFailure.EMPTY_MERGE)
            val site = incoming.first().site
            if (incoming.any { it.site.identity != site.identity })
                return Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH)
            return Refinement.Refined(create(site, incoming.flatMap { it.branches }))
        }

        private fun create(site: ValueSite, branches: Collection<RepresentationBranch>): RepresentationEvidence =
            RepresentationEvidence(site, Collections.unmodifiableSet(branches.toSet()))
    }
}

private fun RepresentationBranch.withHistory(
    step: RepresentationHistory,
    next: RepresentationCurrent = current,
): RepresentationBranch = RepresentationBranch(next, Collections.unmodifiableList(history + step))

private fun admitModelCall(
    input: ValueSite,
    output: ValueSite,
    invocation: ValueInvocation,
    boundInput: ExactModelCallablePosition,
    boundOutput: ExactModelCallablePosition,
): Refinement<Unit, RepresentationPropagationFailure> {
    when (val admission = admitInputPosition(input, boundInput)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    val inputRole = input.role
    if (inputRole !is ValueRole.Argument || inputRole.call.identity != invocation.identity)
        return Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH)
    return admitOutputPosition(output, invocation, boundOutput)
}

private fun admitInputPosition(
    site: ValueSite,
    binding: ExactModelCallablePosition,
): Refinement<Unit, RepresentationPropagationFailure> {
    if (site.basis != binding.endpoint.lease.identity)
        return Refinement.Rejected(RepresentationPropagationFailure.BASIS_MISMATCH)
    val role = site.role
    if (role !is ValueRole.Argument) return Refinement.Rejected(RepresentationPropagationFailure.POSITION_MISMATCH)
    if (binding.position != ModelValuePosition.Argument(role.position))
        return Refinement.Rejected(RepresentationPropagationFailure.POSITION_MISMATCH)
    if (!binding.endpoint.sameModelCallable(role.call.callable))
        return Refinement.Rejected(RepresentationPropagationFailure.CALLABLE_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun admitOutputPosition(
    site: ValueSite,
    invocation: ValueInvocation,
    binding: ExactModelCallablePosition,
): Refinement<Unit, RepresentationPropagationFailure> =
    when {
        site.basis != invocation.basis || site.basis != binding.endpoint.lease.identity ->
            Refinement.Rejected(RepresentationPropagationFailure.BASIS_MISMATCH)
        site.role != ValueRole.ExpressionResult ||
            site.range != invocation.range ||
            !site.enclosing.sameModelCallable(invocation.enclosing) ->
            Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH)
        binding.position != ModelValuePosition.Result ->
            Refinement.Rejected(RepresentationPropagationFailure.POSITION_MISMATCH)
        !binding.endpoint.sameModelCallable(invocation.callable) ->
            Refinement.Rejected(RepresentationPropagationFailure.CALLABLE_MISMATCH)
        else -> Refinement.Refined(Unit)
    }

private fun RelationEndpoint.sameModelCallable(other: RelationEndpoint): Boolean =
    lease.identity == other.lease.identity &&
        compilerIdentity == other.compilerIdentity &&
        file == other.file &&
        range == other.range
