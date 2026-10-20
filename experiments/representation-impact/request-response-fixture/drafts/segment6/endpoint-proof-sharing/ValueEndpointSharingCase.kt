package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector

enum class EndpointSharingVariant {
    SUBJECT,
    RESOLVED,
}

/** Assumed compiler proofs isolate endpoint allocation from native reads and body summaries. */
internal class ValueEndpointSharingCase(val variant: EndpointSharingVariant, generation: Long = 1) {
    private val prototypes =
        mapOf(
                "root" to flowEndpoint("root", 1000, 1300, listOf("String"), generation),
                "bridge" to flowEndpoint("bridge", 600, 900, listOf("String"), generation),
                "host" to flowEndpoint("host", 0, 200, listOf("String"), generation),
                "callee" to flowEndpoint("callee", 300, 500, listOf("String"), generation),
                "field" to flowEndpoint("field", 910, 930, generation = generation, propertyType = "String"),
                "type" to predicateOracleClass(generation),
            )
            .mapValues { (_, endpoint) -> endpoint as RelationEndpoint.Resolved }
    val canonical = prototypes.mapValues { (name, _) -> endpoint(name) }
    private val zero = ValueArgumentPosition.parse(0).flowRefined()
    val prior = observation(shared = true)
    val fresh = observation()

    fun observation(
        shared: Boolean = false,
        hostScope: SymbolSearchScope = prototypes.getValue("host").scope,
        hostConstraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        actualVariant: EndpointSharingVariant = variant,
    ): ValueFlowStep {
        fun proof(name: String): RelationEndpoint =
            if (shared) canonical.getValue(name) else endpoint(name, hostScope, hostConstraints, actualVariant)
        val first = direct(proof("root"), proof("bridge"), proof("root"), proof("bridge"), 1020, 1040)
        val second = direct(proof("bridge"), proof("host"), proof("bridge"), proof("host"), 620, 640)
        val stack = ValueCallStack.Empty.enter(first).flowRefined().enter(second).flowRefined()
        val path =
            flowPath(
                ValuePathSegment.AnyElement,
                ValuePathSegment.Field(ValueField.fromCompiler(proof("field")).flowRefined()),
            )
        val predicates = predicates(stack, path, ::proof)
        val source = source(path, stack, predicates, ::proof)
        val question =
            if (
                hostScope == prototypes.getValue("host").scope &&
                    hostConstraints == SymbolDiscoveryConstraints.None &&
                    actualVariant == variant
            )
                canonical.getValue("host")
            else proof("host")
        return step(source, transfer(source, ::proof), question)
    }

    private fun source(
        path: ValueAccessPath,
        stack: ValueCallStack,
        predicates: ValuePredicates,
        proof: (String) -> RelationEndpoint,
    ): ValueSite {
        val call =
            ValueInvocation.fromCompiler(
                    proof("host"),
                    flowRange(20, 40),
                    proof("callee"),
                    ValueExecutionOwner.declaration(proof("host")),
                )
                .flowRefined()
        return ValueSite.fromCompiler(
                proof("host"),
                flowRange(24, 30),
                ValueRole.Argument(call, zero),
                ValueExecutionOwner.declaration(proof("host")),
                path,
                stack,
                predicates,
            )
            .flowRefined()
    }

    private fun transfer(source: ValueSite, proof: (String) -> RelationEndpoint): ValueTransfer {
        val call = (source.role as ValueRole.Argument).call
        val parameter =
            ValueFormalParameter.fromCompiler(
                    proof("callee"),
                    zero,
                    SymbolDiscoveryCandidateName.parse("value").flowRefined(),
                    flowRange(310, 320),
                    ValueExecutionOwner.declaration(proof("callee")),
                )
                .flowRefined()
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(proof("callee"))).flowRefined()
        val position = ValueFormalPosition.Parameter(parameter)
        val target =
            ValueSite.fromCompiler(
                    proof("callee"),
                    parameter.range,
                    ValueRole.Formal(position),
                    ValueExecutionOwner.declaration(proof("callee")),
                    source.valuePath,
                    source.callStack.enter(frame).flowRefined(),
                    source.predicates,
                )
                .flowRefined()
        return ValueTransfer.fromCompiler(
                source,
                target,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(frame, position)),
            )
            .flowRefined()
    }

    private fun endpoint(
        name: String,
        hostScope: SymbolSearchScope = prototypes.getValue("host").scope,
        hostConstraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        actualVariant: EndpointSharingVariant = variant,
    ): RelationEndpoint {
        val original = prototypes.getValue(name)
        val scope = if (name == "host") hostScope else original.scope
        val constraints = if (name == "host") hostConstraints else original.constraints
        return when (if (name == "type") EndpointSharingVariant.RESOLVED else actualVariant) {
            EndpointSharingVariant.SUBJECT ->
                RelationEndpoint.subject(SymbolSelector.issue(original.lease, scope, original.evidence, constraints))
            EndpointSharingVariant.RESOLVED ->
                RelationEndpoint.resolve(original.lease, scope, original.evidence, constraints).flowRefined()
        }
    }

    private fun direct(
        host: RelationEndpoint,
        callable: RelationEndpoint,
        execution: RelationEndpoint,
        callee: RelationEndpoint,
        start: Int,
        end: Int,
    ): ValueCallFrame =
        ValueCallFrame.direct(
                ValueInvocation.fromCompiler(
                        host,
                        flowRange(start, end),
                        callable,
                        ValueExecutionOwner.declaration(execution),
                    )
                    .flowRefined(),
                ValueExecutionOwner.declaration(callee),
            )
            .flowRefined()

    private fun predicates(
        stack: ValueCallStack,
        path: ValueAccessPath,
        proof: (String) -> RelationEndpoint,
    ): ValuePredicates {
        fun subject(start: Int) =
            ValuePredicateSubject.fromCompiler(
                ValueSite.fromCompiler(
                        proof("host"),
                        flowRange(start, start + 2),
                        ValueRole.LocalRead,
                        ValueExecutionOwner.declaration(proof("host")),
                        path,
                        stack,
                    )
                    .flowRefined()
            )
        return ValuePredicates.fromCompiler(
                listOf(
                    ValuePredicate.NonNull.fromCompiler(subject(10)),
                    ValuePredicate.Subtype.fromCompiler(subject(14), proof("type")).flowRefined(),
                )
            )
            .flowRefined()
    }

    private fun step(source: ValueSite, transfer: ValueTransfer, subject: RelationEndpoint): ValueFlowStep =
        ValueFlowStep.fromCompiler(
                source = source,
                transfers = listOf(transfer),
                obligations = listOf(ValueFlowObligation(source, ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)),
                terminal = ValueFlowTerminal.Unresolved,
                domain =
                    RelationRequest.start(
                        subject,
                        RelationMeaning.Callees,
                        RelationBudget(
                            ResourceBudget(
                                ResultLimit.parse(4).flowRefined(),
                                WorkUnitLimit.parse(100).flowRefined(),
                                ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                            ),
                            RelationByteLimit.parse(1_000_000).flowRefined(),
                        ),
                        RelationSearchBoundary.WORKSPACE_EXPANSION,
                    ),
                examinedWorkUnits = RelationWorkCount.parse(3).flowRefined(),
            )
            .flowRefined()
}
