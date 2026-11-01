package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ReviewedPositionFixture(
    operation: ReviewedLibraryOperation = ReviewedLibraryOperation.ITERABLE_ASSOCIATE,
    generation: Long = 1,
) {
    val base = ReviewedValueFixture(generation)
    private val sorted = operation == ReviewedLibraryOperation.ITERABLE_SORTED_BY
    private val selected =
        ReviewedLibraryCallable.admit(
                base.target(
                    qualified = if (sorted) "kotlin.collections.sortedBy" else "kotlin.collections.associate",
                    parameters = listOf(if (sorted) "(T)->R?" else "(T)->kotlin/Pair<K,V>"),
                    typeParameters = if (sorted) 2 else 3,
                ),
                base.position(0),
            )
            .flowRefined()
    val callback =
        ReviewedCallbackValueBinding.admit(
                enclosing = base.enclosing,
                body = base.body,
                occurrence = base.occurrence,
                supplier = base.supplier,
                selected = selected,
                artifact =
                    ReviewedLibraryArtifact.admit(selected, base.digest(BINARY), base.digest(SOURCES)).flowRefined(),
            )
            .flowRefined()
    private val owner = ValueExecutionOwner.reviewedCallback(callback)

    fun construct(slot: PairSlot, start: Int = 440): ReviewedValueTransfer {
        val binding = ordinary(ReviewedLibraryOperation.TO, start)
        val role =
            if (slot == PairSlot.FIRST) ValueRole.Receiver(binding.invocation, ValueReceiverKind.EXTENSION)
            else ValueRole.Argument(binding.invocation, base.position(0))
        val source =
            ValueSite.fromCompiler(
                    enclosing = base.enclosing,
                    range = flowRange(start, start + 1),
                    role = role,
                    executionOwner = owner,
                    valuePath = base.fieldPath,
                )
                .flowRefined()
        return ReviewedValueTransfer.tupleConstruct(source, binding).flowRefined()
    }

    fun associate(slot: PairSlot, start: Int = 440): ReviewedValueTransfer =
        ReviewedValueTransfer.associatedReturn(returned(construct(slot, start).target.valuePath), callback)
            .flowRefined()

    fun returned(path: ValueAccessPath): ValueSite = base.source(path = path, owner = owner)

    fun getter(slot: PairSlot): ReviewedCallValueBinding =
        ordinary(
            if (slot == PairSlot.FIRST) ReviewedLibraryOperation.PAIR_FIRST else ReviewedLibraryOperation.PAIR_SECOND,
            451,
        )

    fun getterSource(path: ValueAccessPath, slot: PairSlot = PairSlot.FIRST): ValueSite =
        ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(451, 452),
                role = ValueRole.Receiver(getter(slot).invocation, ValueReceiverKind.DISPATCH),
                executionOwner = owner,
                valuePath = path,
            )
            .flowRefined()

    fun collectionReceiver(
        path: ValueAccessPath = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(base.field))
    ): ValueSite {
        val target = RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, selected.target).flowRefined()
        val invocation = ValueInvocation.fromCompiler(base.enclosing, base.occurrence.range, target).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(424, 433),
                role = ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
                valuePath = path,
            )
            .flowRefined()
    }

    fun ordinary(operation: ReviewedLibraryOperation, start: Int = 440): ReviewedCallValueBinding {
        val selected = ReviewedOrdinaryLibraryCallable.admit(ordinaryTarget(operation)).flowRefined()
        val endpoint =
            RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, selected.target).flowRefined()
        val invocation =
            if (operation in setOf(ReviewedLibraryOperation.PAIR_FIRST, ReviewedLibraryOperation.PAIR_SECOND))
                ValueInvocation.getterFromCompiler(base.enclosing, flowRange(start, start + 5), endpoint, owner)
                    .flowRefined()
            else
                ValueInvocation.fromCompiler(base.enclosing, flowRange(start, start + 5), endpoint, owner).flowRefined()
        return ReviewedCallValueBinding.admit(
                invocation,
                selected,
                ReviewedLibraryArtifact.admit(selected, base.digest(BINARY), base.digest(SOURCES)).flowRefined(),
            )
            .flowRefined()
    }

    fun ordinaryTarget(operation: ReviewedLibraryOperation): CompilerGroundedSymbolEvidence {
        val name =
            when (operation) {
                ReviewedLibraryOperation.TO -> "kotlin.to"
                ReviewedLibraryOperation.PAIR_CONSTRUCTOR -> "kotlin.Pair.<init>"
                ReviewedLibraryOperation.PAIR_FIRST -> "kotlin.Pair.first"
                else -> "kotlin.Pair.second"
            }
        return CompilerGroundedSymbolEvidence.fromBoundary(
                file =
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse(
                                if (operation == ReviewedLibraryOperation.TO)
                                    "jar:///library.jar!/kotlin/TuplesKt.class"
                                else "jar:///library.jar!/kotlin/Pair.class"
                            )
                            .flowRefined()
                    ),
                rawStartInclusive = 1000,
                rawEndExclusive = 1100,
                rawName = name.substringAfterLast('.'),
                rawQualifiedIdentity = name,
                kind = ordinaryKind(operation),
                signature = ordinarySignature(operation, name),
            )
            .flowRefined()
    }

    private fun ordinaryKind(operation: ReviewedLibraryOperation): CompilerSymbolKind =
        when (operation) {
            ReviewedLibraryOperation.PAIR_FIRST,
            ReviewedLibraryOperation.PAIR_SECOND -> CompilerSymbolKind.PROPERTY
            ReviewedLibraryOperation.TO -> CompilerSymbolKind.FUNCTION
            else -> CompilerSymbolKind.CONSTRUCTOR
        }

    private fun ordinarySignature(operation: ReviewedLibraryOperation, name: String): CanonicalCompilerSignature {
        val getter = operation in setOf(ReviewedLibraryOperation.PAIR_FIRST, ReviewedLibraryOperation.PAIR_SECOND)
        return if (getter)
            CanonicalCompilerSignature.property(
                    rawQualifiedIdentity = name,
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawReturnType = if (operation == ReviewedLibraryOperation.PAIR_FIRST) "A" else "B",
                )
                .flowRefined()
        else ordinaryFunctionSignature(operation, name)
    }

    private fun ordinaryFunctionSignature(
        operation: ReviewedLibraryOperation,
        name: String,
    ): CanonicalCompilerSignature =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = name,
                rawReceiverType = if (operation == ReviewedLibraryOperation.TO) "A" else null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes =
                    if (operation == ReviewedLibraryOperation.TO) listOf("B") else listOf("A", "B"),
                rawTypeParameterCount = if (operation == ReviewedLibraryOperation.TO) 2 else 0,
            )
            .flowRefined()

    companion object {
        const val BINARY = "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"
        const val SOURCES = "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
    }
}
