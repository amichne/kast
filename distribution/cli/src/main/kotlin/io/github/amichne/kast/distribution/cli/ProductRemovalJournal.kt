package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.managed.removalProcessUid
import kotlinx.serialization.Serializable

@Serializable
internal enum class ProductRemovalPhase {
    PREPARING,
    PREPARED,
}

@Serializable
internal enum class ImmutableRemovalKind {
    RUNTIME,
    BASE,
}

@Serializable
internal data class ImmutableProductRemoval(
    val kind: ImmutableRemovalKind,
    val archiveSha256: String,
    val identity: InstallationFilesystemIdentity,
    val recordSha256: String,
    val manifestSha256: String,
    val provenanceSha256: String,
)

/** Wire evidence retained by the existing uninstall journal, never an independent transaction. */
@Serializable
internal data class ProductRemovalProof(
    val schemaVersion: Int,
    val phase: ProductRemovalPhase,
    val executionIdentity: InstallationFilesystemIdentity,
    val installationManifestSha256: String,
    val immutableRemovals: List<ImmutableProductRemoval>,
    val runtimeStoreIdentity: InstallationFilesystemIdentity?,
    val baseStoreIdentity: InstallationFilesystemIdentity?,
)

internal fun requireProductRemovalProof(journal: UninstallRetirementJournal) {
    val proof = journal.productRemoval ?: return
    if (journal.product == null || proof.schemaVersion != 1)
        throw ManagementRejected("uninstall-recovery", "product removal evidence is invalid")
    val phaseMatches =
        when (proof.phase) {
            ProductRemovalPhase.PREPARING -> journal.type == UninstallRetirementType.REMOVAL_REQUESTED
            ProductRemovalPhase.PREPARED -> journal.type != UninstallRetirementType.REMOVAL_REQUESTED
        }
    if (!phaseMatches || !validRemovalInventory(proof))
        throw ManagementRejected("uninstall-recovery", "product removal phase or inventory is invalid")
    requireRemovalBindings(proof)
}

private fun validRemovalInventory(proof: ProductRemovalProof): Boolean =
    proof.immutableRemovals.size <= MAXIMUM_CONTAINER_ENTRIES &&
        proof.immutableRemovals.map { it.kind to it.archiveSha256 }.distinct().size == proof.immutableRemovals.size &&
        proof.immutableRemovals.all {
            when (it.kind) {
                ImmutableRemovalKind.RUNTIME -> proof.runtimeStoreIdentity != null
                ImmutableRemovalKind.BASE -> proof.baseStoreIdentity != null
            }
        }

private fun requireRemovalBindings(proof: ProductRemovalProof) {
    val identities =
        listOfNotNull(proof.executionIdentity, proof.runtimeStoreIdentity, proof.baseStoreIdentity) +
            proof.immutableRemovals.map { it.identity }
    val digests =
        listOf(proof.installationManifestSha256) +
            proof.immutableRemovals.flatMap {
                listOf(it.archiveSha256, it.recordSha256, it.manifestSha256, it.provenanceSha256)
            }
    if (identities.any { !validRemovalIdentity(it) } || digests.any { !productDigest(it) })
        throw ManagementRejected("uninstall-recovery", "product removal evidence is invalid")
}

private fun validRemovalIdentity(identity: InstallationFilesystemIdentity): Boolean =
    identity.device >= 0 && identity.inode >= 0 && identity.owner == removalProcessUid
