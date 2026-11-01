package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal sealed interface MapValuesPlacementOutcome {
    data class AdmissionRejected(val cause: ReviewedCallbackValueFailure) : MapValuesPlacementOutcome

    data class TransferRejected(val cause: ValueFlowUnsupportedCause) : MapValuesPlacementOutcome

    data class Observed(val transfer: ReviewedValueTransfer) : MapValuesPlacementOutcome
}

/** This fixture calls current admission and transfer owners; it never supplies a desired transfer. */
internal class ReviewedMapValuesPlacementFixture {
    val base = ReviewedPositionFixture().base

    fun read(source: (ReviewedCallbackValueBinding) -> ValueSite): MapValuesPlacementOutcome {
        val selected =
            when (val value = ReviewedLibraryCallable.admit(target(), base.position(0))) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return MapValuesPlacementOutcome.AdmissionRejected(value.failure)
            }
        val artifact =
            when (
                val value =
                    ReviewedLibraryArtifact.admit(
                        selected,
                        base.digest(ReviewedPositionFixture.BINARY),
                        base.digest(ReviewedPositionFixture.SOURCES),
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return MapValuesPlacementOutcome.AdmissionRejected(value.failure)
            }
        val binding =
            when (
                val value =
                    ReviewedCallbackValueBinding.admit(
                        enclosing = base.enclosing,
                        body = base.body(434, 459, "kotlin/collections/Map.Entry<kotlin/String,kast/repro/WireValue>"),
                        occurrence = base.occurrence,
                        supplier = base.supplier,
                        selected = selected,
                        artifact = artifact,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return MapValuesPlacementOutcome.AdmissionRejected(value.failure)
            }
        return when (val value = ReviewedValueTransfer.callbackResult(source(binding), binding)) {
            is Refinement.Refined -> MapValuesPlacementOutcome.Observed(value.value)
            is Refinement.Rejected -> MapValuesPlacementOutcome.TransferRejected(value.failure)
        }
    }

    fun returned(binding: ReviewedCallbackValueBinding, path: ValueAccessPath = base.fieldPath): ValueSite =
        base.source(path = path, owner = ValueExecutionOwner.reviewedCallback(binding))

    private fun target() =
        base.target(
            qualified = "kotlin.collections.mapValues",
            receiver = "kotlin/collections/Map<out K,V>",
            parameters = listOf("(kotlin/collections/Map.Entry<K,V>)->R"),
            typeParameters = 3,
            file =
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///library.jar!/kotlin/collections/MapsKt.class").flowRefined()
                ),
        )
}
