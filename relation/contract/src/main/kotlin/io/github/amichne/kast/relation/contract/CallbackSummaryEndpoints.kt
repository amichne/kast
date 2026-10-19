package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

fun CallbackParameterSummary.requiredCompilerDeclarations(): Set<CompilerGroundedSymbolEvidence> =
    CallbackSummaryEndpoints.declarations(this)

internal class CallbackSummaryEndpoints private constructor() {
    private val endpoints = linkedSetOf<RelationEndpoint>()
    private val files = linkedSetOf<SymbolDiscoveryFileIdentity>()
    private val declarations = linkedSetOf<CompilerGroundedSymbolEvidence>()

    private fun endpoint(value: RelationEndpoint) {
        endpoints += value
        files += value.file
    }

    private fun call(value: ValueInvocation) {
        endpoint(value.enclosing)
        endpoint(value.callable)
    }

    private fun site(value: ValueSite) {
        endpoint(value.enclosing)
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

    private fun formal(value: CallbackParameterIdentity) {
        endpoint(value.callable)
        files += value.parameter.file
    }

    private fun edge(value: CallbackParameterForwarding) {
        formal(value.source)
        binding(CallbackBindingEvidence.Bound(value.target))
        files += value.argument.file
        transfers(value.callableTransfers)
    }

    private fun invocation(value: CallbackParameterInvocation) {
        files += value.occurrence.file
        files += value.owner.file
        transfers(value.callableTransfers)
        value.forwardings.forEach(::edge)
    }

    private fun binding(value: CallbackBindingEvidence) {
        when (value) {
            is CallbackBindingEvidence.Bound -> {
                call(value.binding.invocation)
                files += value.binding.invocationOwner.file
                files += value.binding.parameter.file
            }
            is CallbackBindingEvidence.Default -> {
                formal(value.binding.parameter)
                files += value.binding.defaultValue.file
            }
            is CallbackBindingEvidence.DeferredDependency -> {
                files += value.binding.occurrence.file
                files += value.binding.owner.file
                files += value.binding.target.file
            }
            is CallbackBindingEvidence.DependencyContract -> {
                files += value.binding.occurrence.file
                files += value.binding.owner.file
                files += value.binding.target.file
            }
            is CallbackBindingEvidence.Direct -> {
                files += value.binding.occurrence.file
                files += value.binding.owner.file
            }
            is CallbackBindingEvidence.Unavailable -> Unit
        }
    }

    private fun owner(value: CallbackBodyBinding) {
        files += value.body.file
        when (val supply = value.supply) {
            is CallbackBodySupply.Invocation -> files += supply.occurrence.file
            is CallbackBodySupply.DirectInvocation -> files += supply.occurrence.file
            is CallbackBodySupply.DefaultParameter -> files += supply.parameter.file
            is CallbackBodySupply.Returned -> files += supply.occurrence.file
            CallbackBodySupply.Stored,
            CallbackBodySupply.Unsupported -> Unit
        }
        binding(value.binding)
        when (val execution = value.execution) {
            CallbackOwnerExecution.Unproven -> Unit
            is CallbackOwnerExecution.Observed -> flow(execution.flow)
            is CallbackOwnerExecution.Immutable -> {
                files += execution.owner.file
                flow(execution.flow)
            }
        }
    }

    private fun flow(value: ImmutableCallbackInvocationFlow) {
        site(value.source)
        value.ownerBindings.forEach(::owner)
        value.origin.requiredEndpoints().forEach(::endpoint)
        files += value.origin.requiredSourceFiles()
        declarations += value.origin.requiredCompilerDeclarations()
        value.uses.forEach { use ->
            use.value.requiredEndpoints().forEach(::endpoint)
            files += use.value.requiredSourceFiles()
            declarations += use.value.requiredCompilerDeclarations()
            when (use) {
                is ImmutableCallbackInvocationUse.Supplied -> {
                    binding(CallbackBindingEvidence.Bound(use.supplier.binding))
                    when (val selection = use.supplier.selection) {
                        is CallbackSupplierSelection.Explicit -> site(selection.argument)
                        is CallbackSupplierSelection.Default -> {
                            binding(CallbackBindingEvidence.Bound(selection.omission.binding))
                            binding(CallbackBindingEvidence.Default(selection.declaration))
                        }
                    }
                    summary(use.summary)
                }
                is ImmutableCallbackInvocationUse.Direct -> binding(CallbackBindingEvidence.Direct(use.binding))
                is ImmutableCallbackInvocationUse.Unused -> Unit
            }
        }
    }

    private fun forwarding(value: CallbackForwardingEvidence) {
        when (value) {
            CallbackForwardingEvidence.InvocationRoutes -> Unit
            is CallbackForwardingEvidence.ExhaustedGraph -> {
                formal(value.graph.root)
                value.graph.formals.forEach(::formal)
                value.graph.forwardings.forEach(::edge)
            }
        }
    }

    private fun flow(value: CallbackInvocationFlow) {
        files += value.body.file
        binding(value.binding)
        value.invocations.forEach(::invocation)
        value.ownerBindings.forEach(::owner)
        forwarding(value.forwarding)
    }

    private fun summary(value: CallbackParameterSummary) {
        formal(value.formal)
        value.invocations.forEach(::invocation)
        value.ownerBindings.forEach(::owner)
        forwarding(value.forwarding)
    }

    companion object {
        fun sourceFiles(summary: CallbackParameterSummary): Set<SymbolDiscoveryFileIdentity> =
            CallbackSummaryEndpoints().also { it.summary(summary) }.files.toSet()

        fun collect(summary: CallbackParameterSummary): List<RelationEndpoint> =
            CallbackSummaryEndpoints().also { it.summary(summary) }.endpoints.toList()

        fun declarations(summary: CallbackParameterSummary): Set<CompilerGroundedSymbolEvidence> =
            CallbackSummaryEndpoints().also { it.summary(summary) }.declarations.toSet()
    }
}
