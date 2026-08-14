package io.github.amichne.kast.change.recovery.contract

import io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.kernel.Refinement

enum class DurableAddDeclarationRecoveryFailure {
    EXISTING_ARTIFACT_MISMATCH,
    STORAGE_UNAVAILABLE,
}

/**
 * Detached proof that exact add-declaration recovery material has reached a durable physical
 * recovery boundary.
 *
 * This value carries no filesystem handle, source-write capability, or rollback authority.
 */
@ConsistentCopyVisibility
data class DurableAddDeclarationRecovery private constructor(
    val material: AddDeclarationRecoveryMaterial,
) {
    companion object {
        /**
         * Proof transition:
         * `AddDeclarationRecoveryMaterial -> DurableAddDeclarationRecovery`.
         *
         * Establishes that the exact material was durably persisted by the recovery adapter before
         * this factory was called. There is no expected failure after the adapter has completed;
         * raw bytes may be extracted only inside that physical adapter.
         */
        fun fromPreparedMaterial(
            material: AddDeclarationRecoveryMaterial,
        ): DurableAddDeclarationRecovery = DurableAddDeclarationRecovery(material)
    }
}

enum class PreparedAddDeclarationRecoveryFailure {
    RECOVERY_MATERIAL_MISMATCH,
}

/**
 * Source-write admission proof that exact revalidation and physical recovery durability agree.
 *
 * The proof intentionally carries no write executor or source filesystem capability.
 */
@ConsistentCopyVisibility
data class PreparedAddDeclarationRecovery private constructor(
    val revalidated: RevalidatedAddDeclaration,
    val durableRecovery: DurableAddDeclarationRecovery,
) {
    companion object {
        /**
         * Proof transition:
         * revalidated add-declaration plus durable recovery to
         * `Refinement<PreparedAddDeclarationRecovery, PreparedAddDeclarationRecoveryFailure>`.
         *
         * Establishes that the physically durable material is the exact recovery material carried
         * by revalidation. The closed expected failure is
         * `PreparedAddDeclarationRecoveryFailure`; raw before-image extraction remains confined to
         * the later physical apply or recovery boundary.
         */
        fun admit(
            revalidated: RevalidatedAddDeclaration,
            durableRecovery: DurableAddDeclarationRecovery,
        ): Refinement<PreparedAddDeclarationRecovery, PreparedAddDeclarationRecoveryFailure> =
            if (revalidated.recovery != durableRecovery.material) {
                Refinement.Rejected(
                    PreparedAddDeclarationRecoveryFailure.RECOVERY_MATERIAL_MISMATCH,
                )
            } else {
                Refinement.Refined(
                    PreparedAddDeclarationRecovery(revalidated, durableRecovery),
                )
            }
    }
}
