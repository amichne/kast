package io.github.amichne.kast.relation.contract

internal fun CallbackInvocationFlowFixture.supplierInventory(
    supplier: CallbackParameterSupplier
): CompleteCallbackSupplierInventory {
    val partition =
        CompleteCallbackSupplierPartition.fromCompiler(
                supplier.formal,
                listOf(supplier),
                emptyList(),
                CallbackInvocationScan.EXHAUSTIVE,
            )
            .value()
    return CompleteCallbackSupplierInventory.fromCompiler(
            supplier.formal,
            RelationScopeFingerprint.from(supplier.formal.callable),
            listOf(partition),
        )
        .value()
}
