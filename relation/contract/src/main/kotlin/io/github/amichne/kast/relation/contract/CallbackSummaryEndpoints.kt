package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class CallbackSummaryEndpoints private constructor() {
    private val values = linkedSetOf<RelationEndpoint>()

    private fun call(value: ValueInvocation) {
        values += value.enclosing
        values += value.callable
    }

    private fun site(value: ValueSite) {
        values += value.enclosing
        when (val role = value.role) {
            is ValueRole.Argument -> call(role.call)
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> Unit
        }
    }

    private fun transfers(values: List<ValueTransfer>) {
        values.forEach {
            site(it.source)
            site(it.target)
        }
    }

    private fun edge(value: CallbackParameterForwarding) {
        values += value.source.callable
        call(value.target.invocation)
        transfers(value.callableTransfers)
    }

    private fun binding(value: CallbackBindingEvidence) {
        when (value) {
            is CallbackBindingEvidence.Bound -> call(value.binding.invocation)
            is CallbackBindingEvidence.Default -> values += value.binding.parameter.callable
            is CallbackBindingEvidence.DependencyContract,
            is CallbackBindingEvidence.Direct,
            is CallbackBindingEvidence.Unavailable -> Unit
        }
    }

    companion object {
        fun sourceFiles(summary: CallbackParameterSummary): Set<SymbolDiscoveryFileIdentity> = buildSet {
            addAll(collect(summary).map { it.file })
            add(summary.formal.parameter.file)
            summary.invocations.forEach { invocation ->
                add(invocation.occurrence.file)
                add(invocation.owner.file)
                invocation.forwardings.forEach {
                    add(it.argument.file)
                    add(it.target.invocationOwner.file)
                }
            }
            summary.ownerBindings.forEach { owner ->
                add(owner.body.file)
                when (val supply = owner.supply) {
                    is CallbackBodySupply.Invocation -> add(supply.occurrence.file)
                    is CallbackBodySupply.DirectInvocation -> add(supply.occurrence.file)
                    is CallbackBodySupply.DefaultParameter -> add(supply.parameter.file)
                    is CallbackBodySupply.Returned -> add(supply.occurrence.file)
                    CallbackBodySupply.Stored,
                    CallbackBodySupply.Unsupported -> Unit
                }
                when (val binding = owner.binding) {
                    is CallbackBindingEvidence.DependencyContract -> {
                        add(binding.binding.occurrence.file)
                        add(binding.binding.owner.file)
                        add(binding.binding.target.file)
                    }
                    is CallbackBindingEvidence.Direct -> {
                        add(binding.binding.occurrence.file)
                        add(binding.binding.owner.file)
                    }
                    is CallbackBindingEvidence.Bound -> add(binding.binding.invocationOwner.file)
                    is CallbackBindingEvidence.Default -> add(binding.binding.defaultValue.file)
                    is CallbackBindingEvidence.Unavailable -> Unit
                }
            }
            when (val forwarding = summary.forwarding) {
                CallbackForwardingEvidence.InvocationRoutes -> Unit
                is CallbackForwardingEvidence.ExhaustedGraph ->
                    forwarding.graph.forwardings.forEach {
                        add(it.argument.file)
                        add(it.target.invocationOwner.file)
                    }
            }
        }

        fun collect(summary: CallbackParameterSummary): List<RelationEndpoint> =
            CallbackSummaryEndpoints().run {
                values += summary.formal.callable
                summary.invocations.forEach { invocation ->
                    transfers(invocation.callableTransfers)
                    invocation.forwardings.forEach(::edge)
                }
                summary.ownerBindings.forEach { binding(it.binding) }
                when (val forwarding = summary.forwarding) {
                    CallbackForwardingEvidence.InvocationRoutes -> Unit
                    is CallbackForwardingEvidence.ExhaustedGraph -> {
                        values += forwarding.graph.root.callable
                        values += forwarding.graph.formals.map { it.callable }
                        forwarding.graph.forwardings.forEach(::edge)
                    }
                }
                values.toList()
            }
    }
}
