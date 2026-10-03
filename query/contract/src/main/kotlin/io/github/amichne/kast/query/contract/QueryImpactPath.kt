package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import java.util.Collections

/** Report evidence is an ordered projection of existing compiler and reviewed model witnesses. */
sealed interface QueryImpactStep {
    val source: ValueSite
    val target: ValueSite

    data class Compiler(val transfer: ValueTransfer) : QueryImpactStep {
        override val source: ValueSite
            get() = transfer.source

        override val target: ValueSite
            get() = transfer.target
    }

    data class ModeledRepresentation(val application: RepresentationModelApplication) : QueryImpactStep {
        override val source: ValueSite
            get() = application.source

        override val target: ValueSite
            get() = application.target
    }

    data class ModeledBoundary(val connection: BoundaryArrival.Connected) : QueryImpactStep {
        override val source: ValueSite
            get() = connection.source.site

        override val target: ValueSite
            get() = connection.target.site
    }
}

sealed interface QueryImpactRepresentation {
    data object NotModeled : QueryImpactRepresentation

    data class Present(val evidence: RepresentationEvidence) : QueryImpactRepresentation
}

enum class QueryImpactExclusionCause {
    OUTSIDE_EXACT_FILE,
    OUTSIDE_DIRECTORY,
}

/**
 * Positive canonical location exclusion retains the full explicit domain. Unknown imported ownership is not exclusion.
 */
class QueryImpactScopeExclusion
private constructor(
    val site: ValueSite,
    val domain: RelationSearchBoundary.Explicit,
    val cause: QueryImpactExclusionCause,
) {
    companion object {
        fun admit(
            site: ValueSite,
            domain: SymbolSearchScope.ExactFile,
        ): Refinement<QueryImpactScopeExclusion, QueryImpactPathFailure> =
            admit(site, RelationSearchBoundary.Explicit(domain))

        fun admit(
            site: ValueSite,
            domain: RelationSearchBoundary.Explicit,
        ): Refinement<QueryImpactScopeExclusion, QueryImpactPathFailure> {
            val workspace =
                site.enclosing.file as? io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(QueryImpactPathFailure.EXCLUSION_UNPROVEN)
            val exact = domain.scope as? SymbolSearchScope.ExactFile
            if (exact != null && workspace.path != exact.file)
                return Refinement.Refined(
                    QueryImpactScopeExclusion(site, domain, QueryImpactExclusionCause.OUTSIDE_EXACT_FILE)
                )
            val directory = domain.directory ?: return Refinement.Rejected(QueryImpactPathFailure.EXCLUSION_UNPROVEN)
            val root = site.enclosing.lease.workspaceRoot.value.replace('\\', '/').trimEnd('/')
            val path = workspace.path.value.replace('\\', '/')
            if (!path.startsWith("$root/")) return Refinement.Rejected(QueryImpactPathFailure.EXCLUSION_UNPROVEN)
            val parent = path.removePrefix("$root/").substringBeforeLast('/', "")
            val selected = directory.directory.value.let { if (it == ".") "" else it }
            val included =
                when (directory.containment) {
                    io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment.DIRECT -> parent == selected
                    io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment.DESCENDANTS ->
                        selected.isEmpty() || parent == selected || parent.startsWith("$selected/")
                }
            return if (included) Refinement.Rejected(QueryImpactPathFailure.EXCLUSION_UNPROVEN)
            else
                Refinement.Refined(QueryImpactScopeExclusion(site, domain, QueryImpactExclusionCause.OUTSIDE_DIRECTORY))
        }
    }
}

/** Terminal accounting remains finite and evidence-bearing; no case is a migration verdict. */
sealed interface QueryImpactTerminal {
    val site: ValueSite

    data class Consumer(val evidence: ConsumerRepresentationEvidence) : QueryImpactTerminal {
        override val site: ValueSite
            get() =
                when (evidence) {
                    is ConsumerRepresentationEvidence.Satisfied -> evidence.evidence.site
                    is ConsumerRepresentationEvidence.Different -> evidence.evidence.site
                    is ConsumerRepresentationEvidence.Unknown -> evidence.evidence.site
                }
    }

    data class ModeledTerminal(val boundary: BoundaryArrival.Terminal) : QueryImpactTerminal {
        override val site: ValueSite
            get() = boundary.source.site
    }

    sealed interface Unresolved : QueryImpactTerminal {
        data class ExecutionStop(val stop: QueryImpactExecutionStop) : Unresolved {
            override val site: ValueSite
                get() = stop.source
        }

        data class ReadRejected(val rejection: QueryImpactReadRejection) : Unresolved {
            override val site: ValueSite
                get() = rejection.source
        }

        data class Flow(val obligation: ValueFlowObligation) : Unresolved {
            override val site: ValueSite
                get() = obligation.site
        }

        data class Boundary(val boundary: BoundaryArrival.Unresolved) : Unresolved {
            override val site: ValueSite
                get() = boundary.source.site
        }
    }

    class SupportedDomainEnd private constructor(val observation: ValueFlowStep) : QueryImpactTerminal {
        override val site: ValueSite
            get() = observation.source

        companion object {
            fun admit(observation: ValueFlowStep): Refinement<SupportedDomainEnd, QueryImpactPathFailure> =
                if (
                    observation.terminal == ValueFlowTerminal.SupportedDomainExhausted &&
                        observation.transfers.isEmpty() &&
                        observation.obligations.isEmpty()
                )
                    Refinement.Refined(SupportedDomainEnd(observation))
                else Refinement.Rejected(QueryImpactPathFailure.TERMINAL_UNPROVEN)
        }
    }

    data class ExplicitScopeExclusion(val exclusion: QueryImpactScopeExclusion) : QueryImpactTerminal {
        override val site: ValueSite
            get() = exclusion.site
    }
}

enum class QueryImpactPathFailure {
    DISCONNECTED_STEP,
    TERMINAL_SITE_MISMATCH,
    REPRESENTATION_SITE_MISMATCH,
    REPRESENTATION_PATH_MISMATCH,
    CONSUMER_REPRESENTATION_MISMATCH,
    EXCLUSION_UNPROVEN,
    TERMINAL_UNPROVEN,
}

/** Construction proves path connectivity, not complete investigation coverage. The query owner alone owns coverage. */
class QueryImpactPath
private constructor(
    val producer: ValueSite,
    val steps: List<QueryImpactStep>,
    val representation: QueryImpactRepresentation,
    val terminal: QueryImpactTerminal,
) {
    val retainedBytes: Long
        get() = retainedStorageBytes()

    val destination: ValueSite
        get() = terminal.site

    val producerBasis
        get() = producer.basis

    val destinationBasis
        get() = destination.basis

    companion object {
        fun fromEvidence(
            producer: ValueSite,
            steps: List<QueryImpactStep>,
            representation: QueryImpactRepresentation,
            terminal: QueryImpactTerminal,
        ): Refinement<QueryImpactPath, QueryImpactPathFailure> {
            val current =
                when (val connected = admitConnectedPath(producer, steps, terminal)) {
                    is Refinement.Refined -> connected.value
                    is Refinement.Rejected -> return connected
                }
            when (val admitted = admitPathRepresentation(producer, steps, current, representation, terminal)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(
                QueryImpactPath(producer, Collections.unmodifiableList(steps.toList()), representation, terminal)
            )
        }
    }

    /** Equality retains materially distinct routes, model versions, states and terminal obligations. */
    override fun equals(other: Any?): Boolean =
        other is QueryImpactPath &&
            producer == other.producer &&
            steps == other.steps &&
            representation == other.representation &&
            terminal == other.terminal

    override fun hashCode(): Int =
        31 * (31 * (31 * producer.hashCode() + steps.hashCode()) + representation.hashCode()) + terminal.hashCode()
}

private fun ConsumerRepresentationEvidence.representationEvidence(): RepresentationEvidence =
    when (this) {
        is ConsumerRepresentationEvidence.Satisfied -> evidence
        is ConsumerRepresentationEvidence.Different -> evidence
        is ConsumerRepresentationEvidence.Unknown -> evidence
    }

private fun List<RepresentationHistory>.supports(producer: ValueSite, steps: List<QueryImpactStep>): Boolean {
    val origin = firstOrNull() as? RepresentationHistory.Origin ?: return false
    if (origin.output.identity != producer.identity) return false
    val pathHistory = drop(1)
    if (pathHistory.size != steps.size) return false
    return pathHistory.zip(steps).all { (history, step) ->
        when (history) {
            is RepresentationHistory.CompilerTransfer ->
                step is QueryImpactStep.Compiler && history.transfer == step.transfer
            is RepresentationModelApplication ->
                step is QueryImpactStep.ModeledRepresentation && history == step.application
            is RepresentationHistory.BoundaryModel ->
                step is QueryImpactStep.ModeledBoundary &&
                    history.reference == step.connection.model.reference &&
                    history.source == step.connection.source.reference &&
                    history.target == step.connection.target.reference
            is RepresentationHistory.Unmodeled,
            is RepresentationHistory.Origin -> false
        }
    }
}

private fun admitConnectedPath(
    producer: ValueSite,
    steps: List<QueryImpactStep>,
    terminal: QueryImpactTerminal,
): Refinement<ValueSite, QueryImpactPathFailure> {
    var current = producer
    for (step in steps) {
        if (step.source.identity != current.identity)
            return Refinement.Rejected(QueryImpactPathFailure.DISCONNECTED_STEP)
        current = step.target
    }
    if (terminal.site.identity != current.identity)
        return Refinement.Rejected(QueryImpactPathFailure.TERMINAL_SITE_MISMATCH)
    val cycle = (terminal as? QueryImpactTerminal.Unresolved.ExecutionStop)?.stop as? QueryImpactExecutionStop.Cycle
    if (cycle != null && (cycle.producer != producer || cycle.prefix != steps))
        return Refinement.Rejected(QueryImpactPathFailure.DISCONNECTED_STEP)
    return Refinement.Refined(current)
}

private fun admitPathRepresentation(
    producer: ValueSite,
    steps: List<QueryImpactStep>,
    current: ValueSite,
    representation: QueryImpactRepresentation,
    terminal: QueryImpactTerminal,
): Refinement<Unit, QueryImpactPathFailure> =
    when (representation) {
        QueryImpactRepresentation.NotModeled ->
            if (terminal is QueryImpactTerminal.Consumer)
                Refinement.Rejected(QueryImpactPathFailure.CONSUMER_REPRESENTATION_MISMATCH)
            else Refinement.Refined(Unit)
        is QueryImpactRepresentation.Present ->
            admitPresentRepresentation(producer, steps, current, representation.evidence, terminal)
    }

private fun admitPresentRepresentation(
    producer: ValueSite,
    steps: List<QueryImpactStep>,
    current: ValueSite,
    evidence: RepresentationEvidence,
    terminal: QueryImpactTerminal,
): Refinement<Unit, QueryImpactPathFailure> =
    when {
        evidence.site.identity != current.identity ->
            Refinement.Rejected(QueryImpactPathFailure.REPRESENTATION_SITE_MISMATCH)
        !evidence.branches.all { it.history.supports(producer, steps) } ->
            Refinement.Rejected(QueryImpactPathFailure.REPRESENTATION_PATH_MISMATCH)
        terminal is QueryImpactTerminal.Consumer && terminal.evidence.representationEvidence() != evidence ->
            Refinement.Rejected(QueryImpactPathFailure.CONSUMER_REPRESENTATION_MISMATCH)
        else -> Refinement.Refined(Unit)
    }
