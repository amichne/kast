package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ReviewedNestedMapValuesFixture {
    val placement = ReviewedMapValuesPlacementFixture()
    val base = placement.base
    val named =
        ((placement.read { placement.returned(it) } as MapValuesPlacementOutcome.Observed).transfer.binding
            as ReviewedCallbackValueBinding)
    val parent = parent()

    fun nested(
        parent: DeclaredCallbackValueBinding = this.parent,
        supplier: RelationCallableBody.Anonymous = parent.body,
        occurrence: RelationOccurrence = named.occurrence,
    ) =
        ReviewedCallbackValueBinding.admit(
            enclosing = base.enclosing,
            body = named.body,
            occurrence = occurrence,
            supplier = supplier,
            parent = parent,
            selected = named.selected,
            artifact = named.artifact,
        )

    fun parent(
        source: ReviewedValueFixture = base,
        body: RelationCallableBody.Anonymous = source.body(400, 459),
        kind: CallbackDependencyInvocationKind = CallbackDependencyInvocationKind.EXACTLY_ONCE,
    ): DeclaredCallbackValueBinding {
        val target =
            source.target(
                qualified = "kotlin.let",
                receiver = "T",
                parameters = listOf("(T)->R"),
                typeParameters = 2,
                file =
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/StandardKt.class").flowRefined()
                    ),
            )
        val contract =
            CallbackDependencyContract.fromCompiler(
                    basis = source.enclosing.lease.identity,
                    occurrence = source.occurrence(390, 459),
                    owner = source.supplier,
                    target = target,
                    position = source.position(0),
                    classDigest =
                        DependencyClassDigest.parse("f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05")
                            .flowRefined(),
                    provenance = CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    invocationKind = kind,
                )
                .flowRefined()
        return DeclaredCallbackValueBinding.admit(
                enclosing = source.enclosing,
                body = body,
                contract = contract,
                resultEffect = DeclaredCallbackResultEffect.UNSPECIFIED,
            )
            .flowRefined()
    }
}
