package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

fun CompleteCallbackSupplierInventory.requiredEndpoints(): List<RelationEndpoint> =
    CallbackSupplierEndpoints(this).endpoints.toList()

fun CompleteCallbackSupplierInventory.requiredSourceFiles(): Set<SymbolDiscoveryFileIdentity> =
    CallbackSupplierEndpoints(this).files.toSet()

fun CompleteCallbackSupplierInventory.requiredCompilerDeclarations(): Set<CompilerGroundedSymbolEvidence> =
    CallbackSupplierEndpoints(this).declarations.toSet()

fun ImmutableCallbackValue.requiredEndpoints(): List<RelationEndpoint> =
    CallbackSupplierEndpoints(this).endpoints.toList()

fun ImmutableCallbackValue.requiredSourceFiles(): Set<SymbolDiscoveryFileIdentity> =
    CallbackSupplierEndpoints(this).files.toSet()

fun ImmutableCallbackValue.requiredCompilerDeclarations(): Set<CompilerGroundedSymbolEvidence> =
    CallbackSupplierEndpoints(this).declarations.toSet()

fun ImmutableCallbackValueOrigin.requiredSourceFiles(): Set<SymbolDiscoveryFileIdentity> =
    CallbackSupplierEndpoints(this).files.toSet()

/** Detached ownership includes occurrences, bodies, and implicit receivers without authority-bearing endpoints. */
private class CallbackSupplierEndpoints {
    val endpoints = linkedSetOf<RelationEndpoint>()
    val files = linkedSetOf<SymbolDiscoveryFileIdentity>()
    val declarations = linkedSetOf<CompilerGroundedSymbolEvidence>()

    constructor(origin: ImmutableCallbackValueOrigin) {
        origin(origin)
    }

    constructor(value: ImmutableCallbackValue) {
        value(value)
    }

    constructor(inventory: CompleteCallbackSupplierInventory) {
        formal(inventory.root)
        inventory.partitions.forEach { partition ->
            formal(partition.formal)
            partition.incoming.forEach(::edge)
            partition.suppliers.forEach(::supplier)
        }
    }

    private fun supplier(supplier: CallbackParameterSupplier) {
        formal(supplier.formal)
        argument(supplier.binding)
        when (val selection = supplier.selection) {
            is CallbackSupplierSelection.Explicit -> site(selection.argument)
            is CallbackSupplierSelection.Default -> {
                argument(selection.omission.binding)
                default(selection.declaration)
            }
        }
        value(supplier.value)
    }

    private fun endpoint(value: RelationEndpoint) {
        endpoints += value
        files += value.file
    }

    private fun formal(value: CallbackParameterIdentity) {
        endpoint(value.callable)
        files += value.parameter.file
    }

    private fun argument(value: CallbackArgumentBinding) {
        call(value.invocation)
        files += value.invocationOwner.file
        files += value.parameter.file
    }

    private fun call(value: ValueInvocation) {
        endpoint(value.enclosing)
        endpoint(value.callable)
    }

    private fun default(value: CallbackDefaultBinding) {
        formal(value.parameter)
        files += value.defaultValue.file
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

    private fun transfer(value: ValueTransfer) {
        site(value.source)
        site(value.target)
    }

    private fun edge(value: CallbackParameterForwarding) {
        formal(value.source)
        argument(value.target)
        files += value.argument.file
        value.callableTransfers.forEach(::transfer)
    }

    private fun invocation(value: CallbackParameterInvocation) {
        files += value.occurrence.file
        files += value.owner.file
        value.callableTransfers.forEach(::transfer)
        value.forwardings.forEach(::edge)
    }

    private fun value(value: ImmutableCallbackValue) {
        site(value.source)
        site(value.destination)
        value.transfers.forEach(::transfer)
        origin(value.origin)
    }

    private fun origin(origin: ImmutableCallbackValueOrigin) {
        files += origin.file
        when (origin) {
            is ImmutableCallbackValueOrigin.Anonymous -> Unit
            is ImmutableCallbackValueOrigin.Named -> {
                endpoint(origin.target)
                receiver(origin.receivers.dispatch)
                receiver(origin.receivers.extension)
            }
            is ImmutableCallbackValueOrigin.Returned -> factory(origin.factory)
        }
    }

    private fun receiver(value: CallbackReferenceReceiver) =
        when (value) {
            is CallbackReferenceReceiver.Bound -> {
                files += value.occurrence.file
            }
            is CallbackReferenceReceiver.Implicit -> {
                files += value.declaration.file
                declarations += value.declaration
            }
            CallbackReferenceReceiver.Absent,
            CallbackReferenceReceiver.Unbound -> Unit
        }

    private fun factory(value: CallbackFactoryReturn) {
        call(value.invocation)
        value(value.returnedValue)
        value.captures.forEach(::capture)
        bodyCalls(value.bodyCalls)
    }

    private fun bodyCalls(value: CallbackFactoryBodyCalls) {
        when (value) {
            CallbackFactoryBodyCalls.NotApplicable -> Unit
            is CallbackFactoryBodyCalls.Exhaustive -> {
                files += value.body.file
                value.calls.forEach { call ->
                    files += call.occurrence.file
                    when (call) {
                        is CallbackFactoryBodyCall.Named -> endpoint(call.target)
                        is CallbackFactoryBodyCall.Captured -> {
                            formal(call.formal)
                            invocation(call.invocation)
                        }
                        is CallbackFactoryBodyCall.Boundary -> Unit
                    }
                }
            }
        }
    }

    private fun capture(value: CallbackFactoryCapture) {
        argument(value.binding)
        when (val selected = value.selection) {
            is CallbackFactoryCaptureSelection.Explicit -> site(selected.value)
            is CallbackFactoryCaptureSelection.Default -> default(selected.declaration)
        }
        when (val content = value.content) {
            CallbackFactoryCaptureContent.Scalar -> Unit
            is CallbackFactoryCaptureContent.Callable -> {
                content.values.forEach(::value)
                content.invocations.forEach(::invocation)
            }
        }
    }
}
