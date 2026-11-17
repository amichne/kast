package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
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
class InstalledProductRuntime
private constructor(
    internal val installation: Path,
    internal val runtime: AdmittedContainerRuntime,
    internal val record: ProductBuildRecord,
    private val revisions: List<ProductInputRevision>,
) {
    /** Recheck the exact files covered by cold admission before workspace effects. */
    internal fun admitCurrent(): Refinement<Unit, ContainerFailure> = containerFiles {
        when {
            !Files.notExists(installation.parent.resolve(SHUTDOWN_FENCE), NOFOLLOW_LINKS) ->
                Refinement.Rejected(ContainerFailure.FENCE_REJECTED)
            revisions.all { it.isCurrent() } -> Refinement.Refined(Unit)
            else -> Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
        }
    }

    companion object {
        fun admit(installation: Path): Refinement<InstalledProductRuntime, ContainerFailure> =
            when (val platform = ContainerPlatform.current()) {
                is Refinement.Rejected -> platform
                is Refinement.Refined -> read(installation, platform.value)
            }

        internal fun read(
            installation: Path,
            platform: ContainerPlatform,
        ): Refinement<InstalledProductRuntime, ContainerFailure> = containerFiles {
            val revisions =
                captureRevisions(installation, listOf("installation.json", PRODUCT_REFERENCE, BUILD_RECORD))
                    ?: return@containerFiles Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            when (val selection = InstalledProductSelection.read(installation, platform)) {
                is Refinement.Rejected -> selection
                is Refinement.Refined -> admitRuntime(installation, selection.value, platform, revisions)
            }
        }

        private fun admitRuntime(
            installation: Path,
            selection: InstalledProductSelection,
            platform: ContainerPlatform,
            productRevisions: List<ProductInputRevision>,
        ): Refinement<InstalledProductRuntime, ContainerFailure> {
            val root = Path.of(selection.reference.runtimeRoot)
            val runtimeRevisions =
                captureRevisions(root, listOf("manifest.json", "runtime.json"))
                    ?: return Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE)
            val admitted =
                when (val runtime = AdmittedContainerRuntime.read(root, platform)) {
                    is Refinement.Rejected -> return runtime
                    is Refinement.Refined -> runtime.value
                }
            if (
                admitted.manifest.version != selection.record.version ||
                    sha256(root.resolve("manifest.json")) != selection.reference.runtimeManifestSha256
            )
                return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            val witnesses = productRevisions + runtimeRevisions
            return if (witnesses.all { it.isCurrent() })
                Refinement.Refined(InstalledProductRuntime(installation, admitted, selection.record, witnesses))
            else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
        }
    }
}

/** Each digest covers these exact filesystem revisions, including changes that restore an old mtime. */
private data class ProductInputRevision(
    val path: Path,
    val device: Long,
    val inode: Long,
    val size: Long,
    val changed: FileTime,
) {
    fun isCurrent(): Boolean = capture(path) == this

    companion object {
        fun capture(path: Path): ProductInputRevision? {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path) return null
            val attributes = Files.readAttributes(path, "unix:dev,ino,size,ctime,isRegularFile", NOFOLLOW_LINKS)
            if (attributes["isRegularFile"] != true) return null
            val device = (attributes["dev"] as? Number)?.toLong() ?: return null
            val inode = (attributes["ino"] as? Number)?.toLong() ?: return null
            val size = (attributes["size"] as? Number)?.toLong() ?: return null
            val changed = attributes["ctime"] as? FileTime ?: return null
            return ProductInputRevision(path, device, inode, size, changed)
        }
    }
}

private fun captureRevisions(root: Path, names: List<String>): List<ProductInputRevision>? = names.map {
    ProductInputRevision.capture(root.resolve(it)) ?: return null
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
    if (!validInstalledProductRecord(record) || reference.schemaVersion != 1 || reference.target != platform.publicName)
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
    if (record.producer is LocalProductProducer)
        return reference.publicVersion == null &&
            reference.policy == ProductSelectionPolicy.PinnedBuild &&
            reference.releaseTag == "developer-v${record.version}"
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
            // Local product bytes exist only in their local directory; repair must not seek them remotely.
            if (selected.value.record.producer is LocalProductProducer)
                return SelectionAdmission.Rejected(BuildSelectionFailure.LOCAL_ARTIFACT_REQUIRED)
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
