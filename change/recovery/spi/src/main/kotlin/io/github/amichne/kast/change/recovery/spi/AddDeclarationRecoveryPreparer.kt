package io.github.amichne.kast.change.recovery.spi

import io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecoveryFailure

sealed interface DurableAddDeclarationRecoveryResult {
    data class Prepared(
        val recovery: DurableAddDeclarationRecovery,
    ) : DurableAddDeclarationRecoveryResult

    data class Rejected(
        val failure: DurableAddDeclarationRecoveryFailure,
    ) : DurableAddDeclarationRecoveryResult
}

fun interface AddDeclarationRecoveryPreparer {
    /**
     * Proof transition:
     * `AddDeclarationRecoveryMaterial -> DurableAddDeclarationRecoveryResult`.
     *
     * A prepared result establishes byte-exact physical durability before return. Expected
     * failures are closed by `DurableAddDeclarationRecoveryFailure`; raw image bytes may be
     * extracted only by the implementing physical adapter.
     */
    fun prepare(material: AddDeclarationRecoveryMaterial): DurableAddDeclarationRecoveryResult
}
