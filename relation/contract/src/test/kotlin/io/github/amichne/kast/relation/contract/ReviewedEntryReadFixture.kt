package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ReviewedEntryReadFixture(val slot: MapEntrySlot = MapEntrySlot.VALUE) {
    val input = ReviewedMapEntryInputFixture()
    private val selectedInput = input.selectedValue(slot)
    val source = input.receiver(selectedInput.valuePath).withState(conditions = selectedInput.conditions).flowRefined()
    val entered = ReviewedValueTransfer.callbackInput(source, input.binding, input.formal()).flowRefined()

    fun binding(
        operation: ReviewedLibraryOperation = ReviewedLibraryOperation.MAP_ENTRY_VALUE,
        start: Int = 445,
    ): ReviewedCallValueBinding {
        val selected = ReviewedOrdinaryLibraryCallable.admit(target(operation)).flowRefined()
        val endpoint =
            RelationEndpoint.resolve(input.base.enclosing.lease, input.base.enclosing.scope, selected.target)
                .flowRefined()
        val invocation =
            if (selected.target.signature is CanonicalCompilerSignature.Property)
                ValueInvocation.getterFromCompiler(
                        input.base.enclosing,
                        flowRange(start, start + 2),
                        endpoint,
                        entered.target.executionOwner,
                    )
                    .flowRefined()
            else
                ValueInvocation.fromCompiler(
                        input.base.enclosing,
                        flowRange(start, start + 2),
                        endpoint,
                        entered.target.executionOwner,
                    )
                    .flowRefined()
        return ReviewedCallValueBinding.admit(
                invocation,
                selected,
                ReviewedLibraryArtifact.admit(
                        selected,
                        input.base.digest(ReviewedPositionFixture.BINARY),
                        input.base.digest(ReviewedPositionFixture.SOURCES),
                    )
                    .flowRefined(),
            )
            .flowRefined()
    }

    fun receiver(binding: ReviewedCallValueBinding): ValueSite =
        ValueSite.fromCompiler(
                enclosing = input.base.enclosing,
                range = flowRange(binding.invocation.range.startInclusive, binding.invocation.range.startInclusive + 1),
                role =
                    ValueRole.Receiver(
                        binding.invocation,
                        if (binding.target.signature is CanonicalCompilerSignature.Property) ValueReceiverKind.DISPATCH
                        else ValueReceiverKind.EXTENSION,
                    ),
                executionOwner = entered.target.executionOwner,
                valuePath = entered.target.valuePath,
                conditions = entered.target.conditions,
            )
            .flowRefined()

    private fun target(operation: ReviewedLibraryOperation): CompilerGroundedSymbolEvidence {
        val property = operation.isEntryProperty()
        val name = operation.entryName()
        val entry =
            if (property) "kotlin/collections/collections.kotlin_builtins" else "kotlin/collections/MapsKt.class"
        return CompilerGroundedSymbolEvidence.fromBoundary(
                file =
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///library.jar!/$entry").flowRefined()
                    ),
                rawStartInclusive = 1000,
                rawEndExclusive = 1100,
                rawName = name.substringAfterLast('.'),
                rawQualifiedIdentity = name,
                kind = if (property) CompilerSymbolKind.PROPERTY else CompilerSymbolKind.FUNCTION,
                signature = operation.entrySignature(name),
            )
            .flowRefined()
    }
}

private fun ReviewedLibraryOperation.isEntryProperty(): Boolean =
    this == ReviewedLibraryOperation.MAP_ENTRY_KEY || this == ReviewedLibraryOperation.MAP_ENTRY_VALUE

private fun ReviewedLibraryOperation.entryName(): String =
    when (this) {
        ReviewedLibraryOperation.MAP_ENTRY_KEY -> "kotlin.collections.Map.Entry.key"
        ReviewedLibraryOperation.MAP_ENTRY_VALUE -> "kotlin.collections.Map.Entry.value"
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1 -> "kotlin.collections.component1"
        else -> "kotlin.collections.component2"
    }

private fun ReviewedLibraryOperation.entrySignature(name: String): CanonicalCompilerSignature =
    if (isEntryProperty())
        CanonicalCompilerSignature.property(
                rawQualifiedIdentity = name,
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawReturnType = if (this == ReviewedLibraryOperation.MAP_ENTRY_KEY) "K" else "V",
            )
            .flowRefined()
    else
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = name,
                rawReceiverType = "kotlin/collections/Map.Entry<K,V>",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = emptyList(),
                rawTypeParameterCount = 2,
            )
            .flowRefined()
