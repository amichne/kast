package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable internal data class BuildRecordReference(val name: String, val sha256: String)

@Serializable
internal data class MainBuildEntry(
    val mainRevision: String,
    val buildRevision: String,
    val sourceTree: String,
    val releaseTag: String,
    val version: String,
    val platformTargets: List<String>,
    val candidate: BuildRecordReference,
    val promotion: BuildRecordReference,
)

@Serializable internal data class MainBuildCatalog(val schemaVersion: Int, val builds: List<MainBuildEntry>)

@Serializable internal data class ProductAsset(val name: String, val sha256: String, val bytes: Long)

@Serializable internal data class ProductProducer(val runId: Long, val runAttempt: Int, val workflow: String)

@Serializable
internal data class ProductInputs(
    val javaReleaseSha256: String,
    val dependencyLocksSha256: String,
    val compositionRecipeSha256: String,
    val donorSha256: String,
)

@Serializable
internal data class NativeCompositionEvidence(
    val baseSha256: String,
    val nativeRuntimeSha256: String,
    val nativeBundleSha256: String,
    val runtimeSha256: String,
)

@Serializable
internal data class NativeQualification(
    val receipt: ProductAsset,
    val producer: ProductProducer,
    val bundleSha256: String,
    val runtimeSha256: String,
    val controlSha256: String,
    val kind: String,
    val outcome: String,
    val composition: NativeCompositionEvidence? = null,
)

@Serializable
internal data class ProductTarget(
    val target: String,
    val inputs: ProductInputs,
    val bundle: ProductAsset,
    val runtime: ProductAsset,
    val qualification: NativeQualification,
    val runtimeBase: ProductAsset? = null,
)

@Serializable
internal data class ProductBuildRecord(
    val schemaVersion: Int,
    val repository: String,
    val sourceRevision: String,
    val sourceTree: String,
    val version: String,
    val producer: ProductProducer,
    val targets: List<ProductTarget>,
)

@Serializable
internal data class ProductBuildEntry(
    val mainRevision: String,
    val releaseTag: String,
    val record: ProductBuildRecord,
    val recordSha256: String,
)

@Serializable internal data class ProductBuildCatalog(val schemaVersion: Int, val builds: List<ProductBuildEntry>)

private const val SPLIT_PRODUCT_RECORD_SCHEMA_VERSION = 3

internal fun validProductRecord(record: ProductBuildRecord): Boolean =
    record.schemaVersion in setOf(2, SPLIT_PRODUCT_RECORD_SCHEMA_VERSION) &&
        record.repository == "amichne/kast" &&
        listOf(record.sourceRevision, record.sourceTree).all(::productRevision) &&
        record.version.length <= MAX_VERSION_CHARACTERS &&
        Regex("0\\.0\\.[1-9][0-9]*").matches(record.version) &&
        validProducer(record.producer, ".github/workflows/ci.yml") &&
        record.targets.map { it.target } == listOf("linux-x86_64", "macos-aarch64") &&
        record.targets.all {
            (it.runtimeBase != null) == (record.schemaVersion == SPLIT_PRODUCT_RECORD_SCHEMA_VERSION) &&
                validProductTarget(it, record.version)
        }

private fun productRevision(value: String): Boolean =
    Regex("[0-9a-f]{40}").matches(value) && value != "0".repeat(REVISION_LENGTH)

internal fun productDigest(value: String): Boolean = Regex("[0-9a-f]{64}").matches(value)

private fun validProducer(producer: ProductProducer, workflow: String): Boolean =
    producer.runId > 0 && producer.runAttempt > 0 && producer.workflow == workflow

private fun validProductAsset(asset: ProductAsset, name: String): Boolean =
    asset.name == name && productDigest(asset.sha256) && asset.bytes in 1 until MAX_PRODUCT_ASSET_BYTES

private fun validProductTarget(target: ProductTarget, version: String): Boolean {
    val evidence = target.qualification
    val inputs = target.inputs
    return listOf(
            inputs.javaReleaseSha256,
            inputs.dependencyLocksSha256,
            inputs.compositionRecipeSha256,
            inputs.donorSha256,
        )
        .all(::productDigest) &&
        validProductAsset(target.bundle, "kast-composed-setup-v$version-${target.target}.tar.gz") &&
        validProductAsset(target.runtime, "kast-runtime-v$version-${target.target}.zip") &&
        validProductAsset(evidence.receipt, "qualification-${target.target}.json") &&
        validProducer(evidence.producer, ".github/workflows/composed-linux.yml") &&
        evidence.kind == "NATIVE" &&
        evidence.outcome == "COMPLETE" &&
        evidence.bundleSha256 == target.bundle.sha256 &&
        evidence.runtimeSha256 == target.runtime.sha256 &&
        productDigest(evidence.controlSha256) &&
        validComposition(target)
}

private fun validComposition(target: ProductTarget): Boolean =
    when (val base = target.runtimeBase) {
        null -> target.qualification.composition == null
        else ->
            when (val proof = target.qualification.composition) {
                null -> false
                else ->
                    validProductAsset(base, "kast-runtime-base-${target.target}-${base.sha256}.zip") &&
                        proof.baseSha256 == base.sha256 &&
                        proof.runtimeSha256 == target.runtime.sha256 &&
                        productDigest(proof.nativeRuntimeSha256) &&
                        productDigest(proof.nativeBundleSha256)
            }
    }

/** A captured discovery snapshot never selects a different entry after platform rejection. */
internal class AdmittedBuildCatalog
private constructor(
    val entries: List<MainBuildEntry>,
    val products: List<ProductBuildEntry> = emptyList(),
) {
    fun selectProduct(request: BuildRequest): SelectionAdmission<ProductBuildEntry> {
        val matches =
            when (request) {
                BuildRequest.Main -> products.take(1)
                is BuildRequest.Commit -> products.filter { it.mainRevision.startsWith(request.selector.value) }
                BuildRequest.Repair,
                BuildRequest.RecordedPolicy,
                BuildRequest.Stable,
                is BuildRequest.Version -> return SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
            }
        return when (matches.size) {
            0 -> SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE)
            1 -> SelectionAdmission.Admitted(matches.single())
            else -> SelectionAdmission.Rejected(BuildSelectionFailure.AMBIGUOUS_COMMIT)
        }
    }

    fun select(request: BuildRequest, platform: String): SelectionAdmission<MainBuildEntry> {
        val matches =
            when (request) {
                BuildRequest.Main -> entries.take(1)
                is BuildRequest.Commit -> entries.filter { it.mainRevision.startsWith(request.selector.value) }
                BuildRequest.Repair,
                BuildRequest.RecordedPolicy,
                BuildRequest.Stable,
                is BuildRequest.Version -> return SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
            }
        return when {
            matches.isEmpty() -> SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE)
            matches.size != 1 -> SelectionAdmission.Rejected(BuildSelectionFailure.AMBIGUOUS_COMMIT)
            platform !in matches.single().platformTargets ->
                SelectionAdmission.Rejected(BuildSelectionFailure.PLATFORM_UNAVAILABLE)
            else -> SelectionAdmission.Admitted(matches.single())
        }
    }

    companion object {
        fun decode(raw: String): SelectionAdmission<AdmittedBuildCatalog> {
            if (raw.length > MAX_CATALOG_CHARACTERS)
                return SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
            val product =
                try {
                    managementJson.decodeFromString<ProductBuildCatalog>(raw)
                } catch (_: SerializationException) {
                    null
                }
            if (product != null && product.schemaVersion == 2) {
                val builds = product.builds
                return if (validProductCatalog(product))
                    SelectionAdmission.Admitted(AdmittedBuildCatalog(emptyList(), builds))
                else SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
            }
            val document =
                try {
                    managementJson.decodeFromString<MainBuildCatalog>(raw)
                } catch (_: SerializationException) {
                    return SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
                }
            val entries = document.builds
            return if (validCatalog(document)) SelectionAdmission.Admitted(AdmittedBuildCatalog(entries.toList()))
            else SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
        }
    }
}

private fun validProductCatalog(catalog: ProductBuildCatalog): Boolean =
    catalog.builds.size <= MAX_CATALOG_ENTRIES &&
        catalog.builds.map { it.mainRevision }.distinct().size == catalog.builds.size &&
        catalog.builds.all(::validProductEntry)

private fun validProductEntry(entry: ProductBuildEntry): Boolean =
    productRevision(entry.mainRevision) &&
        entry.mainRevision == entry.record.sourceRevision &&
        validProductRecord(entry.record) &&
        entry.releaseTag == "developer-v${entry.record.version}" &&
        productDigest(entry.recordSha256)

private fun validEntry(entry: MainBuildEntry): Boolean =
    listOf(entry.mainRevision, entry.buildRevision, entry.sourceTree).all {
        Regex("[0-9a-f]{40}").matches(it) && it != "0".repeat(REVISION_LENGTH)
    } &&
        Regex("0\\.0\\.[1-9][0-9]*").matches(entry.version) &&
        entry.releaseTag == "developer-v${entry.version}" &&
        entry.platformTargets.isNotEmpty() &&
        entry.platformTargets.distinct() == entry.platformTargets &&
        entry.platformTargets.all { Regex("[a-z0-9]+-[a-z0-9]+").matches(it) } &&
        entry.candidate.name == "candidate.json" &&
        entry.promotion.name == "promotion.json" &&
        listOf(entry.candidate, entry.promotion).all { Regex("[0-9a-f]{64}").matches(it.sha256) }

private const val MAX_CATALOG_CHARACTERS = 65_536
private const val MAX_CATALOG_ENTRIES = 5
private const val REVISION_LENGTH = 40
private const val MAX_VERSION_CHARACTERS = 64
private const val MAX_PRODUCT_ASSET_BYTES = 2_147_483_648L

private fun validCatalog(document: MainBuildCatalog): Boolean =
    document.schemaVersion == 1 && document.builds.size <= MAX_CATALOG_ENTRIES && validEntries(document.builds)

private fun validEntries(entries: List<MainBuildEntry>): Boolean =
    entries.map { it.mainRevision }.distinct().size == entries.size && entries.all(::validEntry)
