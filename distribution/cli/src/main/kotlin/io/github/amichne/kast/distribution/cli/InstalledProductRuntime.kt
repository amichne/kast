package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Installer-owned selection, covered by the active Control's payload manifest. */
@Serializable
internal data class InstalledProductReference(
    val releaseTag: String,
    val publicVersion: String?,
    val version: String,
    val sourceRevision: String,
    val sourceTree: String,
    val buildRecordSha256: String,
    val target: String,
    val bundleSha256: String,
    val qualificationSha256: String,
    val runtimeRoot: String,
    val runtimeArchiveSha256: String,
    val runtimeManifestSha256: String,
    val policy: ProductSelectionPolicy,
    @Required val schemaVersion: Int = 1,
)

/** Local admission never acquires release assets or substitutes another runtime. */
internal class InstalledProductRuntime
private constructor(
    private val installation: Path,
    private val selection: InstalledProductSelection,
    val runtime: AdmittedContainerRuntime,
) {
    val record: ProductBuildRecord
        get() = selection.record

    /** Recheck under the request lease or publication lock before changing workspace state. */
    fun admitCurrent(): Refinement<Unit, ContainerFailure> = containerFiles {
        if (
            !java.nio.file.Files.notExists(
                installation.parent.resolve(SHUTDOWN_FENCE),
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
        )
            return@containerFiles Refinement.Rejected(ContainerFailure.FENCE_REJECTED)
        when (val current = InstalledProductSelection.read(installation, runtime.manifest.platform)) {
            is Refinement.Rejected -> current
            is Refinement.Refined ->
                if (current.value.reference == selection.reference && current.value.record == record)
                    Refinement.Refined(Unit)
                else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
        }
    }

    companion object {
        fun read(
            installation: Path,
            platform: ContainerPlatform,
        ): Refinement<InstalledProductRuntime, ContainerFailure> =
            when (val selection = InstalledProductSelection.read(installation, platform)) {
                is Refinement.Rejected -> selection
                is Refinement.Refined ->
                    containerFiles {
                        admitRuntime(installation, selection.value, platform)
                    }
            }

        private fun admitRuntime(
            installation: Path,
            selection: InstalledProductSelection,
            platform: ContainerPlatform,
        ): Refinement<InstalledProductRuntime, ContainerFailure> =
            when (val runtime = AdmittedContainerRuntime.read(Path.of(selection.reference.runtimeRoot), platform)) {
                is Refinement.Rejected -> runtime
                is Refinement.Refined ->
                    if (
                        runtime.value.manifest.version == selection.record.version &&
                            sha256(runtime.value.root.resolve("manifest.json")) ==
                                selection.reference.runtimeManifestSha256
                    )
                        Refinement.Refined(InstalledProductRuntime(installation, selection, runtime.value))
                    else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            }
    }
}

/** Selection ownership is available for repair even when selected runtime bytes are missing. */
internal class InstalledProductSelection
private constructor(
    val reference: InstalledProductReference,
    val record: ProductBuildRecord,
) {
    companion object {
        fun read(
            installation: Path,
            platform: ContainerPlatform,
        ): Refinement<InstalledProductSelection, ContainerFailure> = containerFiles {
            if (!installedProductPayloadOwned(installation))
                return@containerFiles Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            val reference =
                when (
                    val read = readContainerRecord<InstalledProductReference>(installation.resolve(PRODUCT_REFERENCE))
                ) {
                    is Refinement.Rejected -> return@containerFiles read
                    is Refinement.Refined -> read.value
                }
            val record =
                when (val read = readContainerRecord<ProductBuildRecord>(installation.resolve(BUILD_RECORD))) {
                    is Refinement.Rejected -> return@containerFiles read
                    is Refinement.Refined -> read.value
                }
            if (!validInstalledProduct(installation, platform, reference, record))
                return@containerFiles Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            Refinement.Refined(InstalledProductSelection(reference, record))
        }
    }
}

private fun installedProductPayloadOwned(installation: Path): Boolean =
    when (val manifest = readBundledManifest(installation)) {
        BundledManifestRead.Invalid,
        BundledManifestRead.Unavailable -> false
        is BundledManifestRead.Read ->
            listOf(PRODUCT_REFERENCE, BUILD_RECORD).all { payloadOwned(installation, manifest.manifest, it) }
    }

private fun validInstalledProduct(
    installation: Path,
    platform: ContainerPlatform,
    reference: InstalledProductReference,
    record: ProductBuildRecord,
): Boolean {
    if (!validProductRecord(record) || reference.schemaVersion != 1 || reference.target != platform.publicName)
        return false
    val target = record.targets.singleOrNull { it.target == reference.target } ?: return false
    val runtimeRoot = installation.parent.resolveSibling("kast-workspaces").resolve("runtimes/${target.runtime.sha256}")
    return reference.buildRecordSha256 == sha256(installation.resolve(BUILD_RECORD)) &&
        reference.bundleSha256 == target.bundle.sha256 &&
        reference.qualificationSha256 == target.qualification.receipt.sha256 &&
        reference.runtimeArchiveSha256 == target.runtime.sha256 &&
        reference.runtimeRoot == runtimeRoot.toString() &&
        productDigest(reference.runtimeManifestSha256) &&
        validInstalledRelease(reference, record)
}

private fun validInstalledRelease(reference: InstalledProductReference, record: ProductBuildRecord): Boolean {
    if (
        reference.version != record.version ||
            reference.sourceRevision != record.sourceRevision ||
            reference.sourceTree != record.sourceTree
    )
        return false
    return when (val version = reference.publicVersion) {
        null ->
            reference.releaseTag == "developer-v${reference.version}" &&
                reference.policy != ProductSelectionPolicy.TrackStable
        else ->
            StableVersion.parse(version) is SelectionAdmission.Admitted &&
                reference.releaseTag == "v$version" &&
                reference.policy != ProductSelectionPolicy.TrackMain
    }
}

internal fun installedRepairSelection(root: Path): SelectionAdmission<ResolvedBuildSelection.Product> {
    val platform =
        when (val current = ContainerPlatform.current()) {
            is Refinement.Rejected -> return SelectionAdmission.Rejected(BuildSelectionFailure.PLATFORM_UNAVAILABLE)
            is Refinement.Refined -> current.value
        }
    return when (val selected = InstalledProductSelection.read(root.resolve("installation"), platform)) {
        is Refinement.Rejected -> SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
        is Refinement.Refined -> {
            val reference = selected.value.reference
            SelectionAdmission.Admitted(
                ResolvedBuildSelection.Product(
                    reference.releaseTag,
                    selected.value.record,
                    reference.buildRecordSha256,
                    reference.policy,
                    reference.publicVersion,
                    if (reference.publicVersion == null) reference.sourceRevision else null,
                )
            )
        }
    }
}

private const val PRODUCT_REFERENCE = "share/kast/product-selection.json"
private const val BUILD_RECORD = "share/kast/build-record.json"
