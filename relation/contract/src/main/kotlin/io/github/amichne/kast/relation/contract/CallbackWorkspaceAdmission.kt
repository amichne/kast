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

private class CallbackWorkspaceFiles {
    private val files = linkedSetOf<SymbolDiscoveryFileIdentity>()

    fun admit(root: CanonicalWorkspaceRoot): Refinement<Unit, StaticCallbackGraphFailure> {
        if (files.any { !it.inside(root) }) return Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace)
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
                files += target.invocation.occurrence.file
                files += target.invocation.owner.file
                target.invocation.callableTransfers.forEach {
                    files += it.source.enclosing.file
                    files += it.target.enclosing.file
                }
                val proof = target.suppliers
                if (proof is CallbackSupplierInventoryEvidence.Exhaustive)
                    files += proof.inventory.requiredSourceFiles()
            }
            is RelationCallableTarget.UnavailableSupply,
            is RelationCallableTarget.UnavailableReference,
            is RelationCallableTarget.SourceLess -> Unit
        }
    }

    fun flow(flow: ImmutableCallbackInvocationFlow) {
        files += flow.source.enclosing.file
        files += flow.origin.requiredSourceFiles()
        flow.uses.forEach { use ->
            when (use) {
                is ImmutableCallbackInvocationUse.Supplied -> {
                    supplier(use.supplier)
                    files += use.summary.requiredSourceFiles()
                }
                is ImmutableCallbackInvocationUse.Direct -> direct(use)
                is ImmutableCallbackInvocationUse.Unused -> files += use.value.requiredSourceFiles()
            }
        }
    }

    private fun supplied(value: CompleteCallbackSupply) {
        supplier(value.supplier)
        files += value.summary.requiredSourceFiles()
    }

    private fun supplier(value: CallbackParameterSupplier) {
        files += value.value.requiredSourceFiles()
        argument(value.binding)
        when (val selection = value.selection) {
            is CallbackSupplierSelection.Explicit -> files += selection.argument.enclosing.file
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
                files += proof.summary.requiredSourceFiles()
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

private fun SymbolDiscoveryFileIdentity.inside(root: CanonicalWorkspaceRoot): Boolean =
    this is SymbolDiscoveryFileIdentity.Workspace &&
        CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of(path.value)) is Refinement.Refined
