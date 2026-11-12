package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val PRODUCT_MANAGEMENT_SCHEMA_VERSION = 3

/** Schema 3 retains the complete product identity; legacy projections are never written back to it. */
@Serializable
internal data class ProductManagementReceipt(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val product: InstalledProductReference,
    val registrations: List<ManagedRegistration> = emptyList(),
    val guidance: List<ManagedGuidance> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val providers: List<ProviderRegistration> = emptyList(),
) {
    fun refine(): ManagementReceipt {
        val identity =
            InstalledBuildIdentity(
                product.releaseTag,
                product.publicVersion ?: product.version,
                product.sourceRevision,
                product.sourceTree,
                if (product.publicVersion == null) product.sourceRevision else null,
            )
        val policy =
            when (product.policy) {
                ProductSelectionPolicy.TrackStable -> UpdatePolicy.TrackStable
                ProductSelectionPolicy.TrackMain -> UpdatePolicy.TrackMain
                ProductSelectionPolicy.PinnedBuild -> UpdatePolicy.PinnedBuild(identity)
            }
        return ManagementReceipt(
            schemaVersion,
            installationRoot,
            executable,
            executableSha256,
            if (product.publicVersion == null) ReleaseChannel.DEVELOPER else ReleaseChannel.STABLE,
            registrations,
            policy,
            identity,
            guidance,
            product,
            providers,
        )
    }
}

internal fun encodeManagementReceipt(receipt: ManagementReceipt): String =
    when (val product = receipt.product) {
        null -> managementJson.encodeToString(receipt)
        else ->
            managementJson.encodeToString(
                ProductManagementReceipt(
                    receipt.schemaVersion,
                    receipt.installationRoot,
                    receipt.executable,
                    receipt.executableSha256,
                    product,
                    receipt.registrations,
                    receipt.guidance,
                    receipt.providers,
                )
            )
    }

internal fun validManagementProduct(root: Path, receipt: ManagementReceipt): Boolean {
    if (java.nio.file.Files.notExists(root.resolve("installation"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return validRetiredProduct(root, receipt)
    val platform =
        when (val current = ContainerPlatform.current()) {
            is Refinement.Rejected -> return false
            is Refinement.Refined -> current.value
        }
    return when (val selected = InstalledProductSelection.read(root.resolve("installation"), platform)) {
        is Refinement.Rejected -> validRetiredProduct(root, receipt)
        is Refinement.Refined -> selected.value.reference == receipt.product
    }
}

/** The existing uninstall journal remains authoritative after its exact Control payload has gone. */
private fun validRetiredProduct(root: Path, receipt: ManagementReceipt): Boolean =
    try {
        val journal = readUninstallRetirement(root)
        requireUninstallRetirement(root, receipt)
        val payload = root.resolve("installation")
        val retired =
            java.nio.file.Files.notExists(payload, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                (journal.productRemoval?.phase == ProductRemovalPhase.PREPARED &&
                    payload.toRealPath() == payload &&
                    uninstallFilesystemIdentity(payload) == journal.controlIdentity)
        journal.type != UninstallRetirementType.REMOVAL_REQUESTED && journal.product == receipt.product && retired
    } catch (_: ManagementRejected) {
        false
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
