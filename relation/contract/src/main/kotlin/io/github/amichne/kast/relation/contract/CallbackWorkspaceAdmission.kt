package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path

/** Checks all retained callback proof files, including context absent from visible graph nodes. */
fun RelationCallableObservation.admitCallbackWorkspace(): Refinement<Unit, StaticCallbackGraphFailure> =
    CallbackWorkspaceFiles().also { it.target(target) }.admit(basis.workspaceRoot)

fun ImmutableCallbackInvocationFlow.admitCallbackWorkspace(
    root: CanonicalWorkspaceRoot
): Refinement<Unit, StaticCallbackGraphFailure> = CallbackWorkspaceFiles().also { it.flow(this) }.admit(root)

fun CallbackInvocationFlow.admitCallbackWorkspace(
    root: CanonicalWorkspaceRoot
): Refinement<Unit, StaticCallbackGraphFailure> = CallbackWorkspaceFiles().also { it.flow(this) }.admit(root)

private class CallbackWorkspaceFiles {
    private val files = linkedSetOf<SymbolDiscoveryFileIdentity>()

    fun admit(root: CanonicalWorkspaceRoot): Refinement<Unit, StaticCallbackGraphFailure> {
        if (files.any { !it.insideCallbackWorkspace(root) })
            return Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace)
        return Refinement.Refined(Unit)
    }

    fun target(target: RelationCallableTarget) {
        when (target) {
            is RelationCallableTarget.CallbackSupplies -> target.supplies.values.forEach(::supplied)
            is RelationCallableTarget.DirectInvocations -> target.invocations.values.forEach(::direct)
            is RelationCallableTarget.NamedReference -> named(target.reference)
            is RelationCallableTarget.ParameterInvocation -> {
                files += target.parameter.callable.file
                files += target.parameter.parameter.file
                invocation(target.invocation)
                val proof = target.suppliers
                if (proof is CallbackSupplierInventoryEvidence.Exhaustive) inventory(proof.inventory)
            }
            is RelationCallableTarget.UnavailableSupply,
            is RelationCallableTarget.UnavailableReference,
            is RelationCallableTarget.SourceLess -> Unit
        }
    }

    private fun inventory(value: CompleteCallbackSupplierInventory) {
        files += value.root.callable.file
        files += value.root.parameter.file
        value.partitions.forEach { partition ->
            files += partition.formal.callable.file
            files += partition.formal.parameter.file
            partition.suppliers.forEach(::supplier)
            partition.incoming.forEach(::edge)
            partition.ownerBindings.forEach(::owner)
        }
    }

    fun flow(flow: ImmutableCallbackInvocationFlow) {
        site(flow.source)
        flow.ownerBindings.forEach(::owner)
        files += flow.origin.requiredSourceFiles()
        flow.uses.forEach { use ->
            when (use) {
                is ImmutableCallbackInvocationUse.Supplied -> {
                    supplier(use.supplier)
                    summary(use.summary)
                }
                is ImmutableCallbackInvocationUse.Direct -> direct(use)
                is ImmutableCallbackInvocationUse.Unused -> files += use.value.requiredSourceFiles()
            }
        }
    }

    private fun supplied(value: CompleteCallbackSupply) {
        supplier(value.supplier)
        summary(value.summary)
    }

    fun flow(value: CallbackInvocationFlow) {
        files += value.body.file
        binding(value.binding)
        value.invocations.forEach(::invocation)
        value.ownerBindings.forEach(::owner)
        forwarding(value.forwarding)
    }

    private fun summary(value: CallbackParameterSummary) {
        files += value.formal.callable.file
        files += value.formal.parameter.file
        value.invocations.forEach(::invocation)
        value.ownerBindings.forEach(::owner)
        forwarding(value.forwarding)
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

    private fun binding(value: CallbackBindingEvidence) {
        when (value) {
            is CallbackBindingEvidence.Bound -> argument(value.binding)
            is CallbackBindingEvidence.Default -> {
                files += value.binding.parameter.callable.file
                files += value.binding.parameter.parameter.file
                files += value.binding.defaultValue.file
            }
            is CallbackBindingEvidence.Direct -> {
                files += value.binding.occurrence.file
                files += value.binding.owner.file
            }
            is CallbackBindingEvidence.DeferredDependency -> {
                files += value.binding.occurrence.file
                files += value.binding.owner.file
            }
            is CallbackBindingEvidence.DependencyContract -> {
                files += value.binding.occurrence.file
                files += value.binding.owner.file
            }
            is CallbackBindingEvidence.Unavailable -> Unit
        }
    }

    private fun invocation(value: CallbackParameterInvocation) {
        files += value.occurrence.file
        files += value.owner.file
        value.callableTransfers.forEach(::transfer)
        value.forwardings.forEach(::edge)
    }

    private fun edge(value: CallbackParameterForwarding) {
        files += value.source.callable.file
        files += value.source.parameter.file
        files += value.argument.file
        argument(value.target)
        value.callableTransfers.forEach(::transfer)
    }

    private fun transfer(value: ValueTransfer) {
        site(value.source)
        site(value.target)
    }

    private fun site(value: ValueSite) {
        files += value.enclosing.file
        value.stateEndpoints().forEach { files += it.file }
        when (val role = value.role) {
            is ValueRole.Argument -> {
                files += role.call.enclosing.file
                files += role.call.callable.file
            }
            is ValueRole.Receiver -> {
                files += role.call.enclosing.file
                files += role.call.callable.file
            }
            is ValueRole.Formal -> Unit
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> Unit
        }
    }

    private fun forwarding(value: CallbackForwardingEvidence) {
        when (value) {
            CallbackForwardingEvidence.InvocationRoutes -> Unit
            is CallbackForwardingEvidence.ExhaustedGraph -> {
                files += value.graph.root.callable.file
                files += value.graph.root.parameter.file
                value.graph.formals.forEach {
                    files += it.callable.file
                    files += it.parameter.file
                }
                value.graph.forwardings.forEach(::edge)
            }
        }
    }

    private fun supplier(value: CallbackParameterSupplier) {
        files += value.value.requiredSourceFiles()
        argument(value.binding)
        when (val selection = value.selection) {
            is CallbackSupplierSelection.Explicit -> site(selection.argument)
            is CallbackSupplierSelection.Default -> {
                files += selection.declaration.defaultValue.file
                files += selection.declaration.parameter.parameter.file
            }
        }
    }

    private fun direct(value: ImmutableCallbackInvocationUse.Direct) {
        files += value.value.requiredSourceFiles()
        files += value.binding.occurrence.file
        files += value.binding.owner.file
    }

    private fun argument(value: CallbackArgumentBinding) {
        files += value.invocation.enclosing.file
        files += value.invocation.callable.file
        files += value.invocationOwner.file
        files += value.parameter.file
    }

    private fun named(value: NamedCallbackReference) {
        files += value.occurrence.file
        files += value.target.file
        receiver(value.receivers.dispatch)
        receiver(value.receivers.extension)
        when (val proof = value.flow) {
            is NamedCallbackReferenceFlow.Immutable -> flow(proof.flow)
            is NamedCallbackReferenceFlow.Supplied -> {
                argument(proof.binding)
                summary(proof.summary)
            }
            is NamedCallbackReferenceFlow.Direct -> {
                files += proof.binding.occurrence.file
                files += proof.binding.owner.file
            }
            is NamedCallbackReferenceFlow.Unavailable -> Unit
        }
    }

    private fun receiver(value: CallbackReferenceReceiver) {
        when (value) {
            is CallbackReferenceReceiver.Bound -> files += value.occurrence.file
            is CallbackReferenceReceiver.Implicit -> files += value.declaration.file
            CallbackReferenceReceiver.Absent,
            CallbackReferenceReceiver.Unbound -> Unit
        }
    }
}

internal fun SymbolDiscoveryFileIdentity.insideCallbackWorkspace(root: CanonicalWorkspaceRoot): Boolean =
    this is SymbolDiscoveryFileIdentity.Workspace &&
        CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of(path.value)) is Refinement.Refined
