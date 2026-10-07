package io.github.amichne.kast.relation.contract

sealed interface CallbackSupplierCacheLookup {
    data object Miss : CallbackSupplierCacheLookup

    data class Found(val inventory: CompleteCallbackSupplierInventory) : CallbackSupplierCacheLookup
}

/** Separate complete reverse-supply partitions, with the entire supplier universe as a negative dependency. */
interface CallbackSupplierCachePort {
    fun find(
        root: CallbackParameterIdentity,
        domain: RelationScopeFingerprint,
        readmit: (CompleteCallbackSupplierInventory) -> CallbackReadmission<CompleteCallbackSupplierInventory>,
    ): CallbackSupplierCacheLookup

    fun retain(inventory: CompleteCallbackSupplierInventory)

    fun admitted(inventory: CompleteCallbackSupplierInventory)

    data object Disabled : CallbackSupplierCachePort {
        override fun find(
            root: CallbackParameterIdentity,
            domain: RelationScopeFingerprint,
            readmit: (CompleteCallbackSupplierInventory) -> CallbackReadmission<CompleteCallbackSupplierInventory>,
        ) = CallbackSupplierCacheLookup.Miss

        override fun retain(inventory: CompleteCallbackSupplierInventory) = Unit

        override fun admitted(inventory: CompleteCallbackSupplierInventory) = Unit
    }
}
