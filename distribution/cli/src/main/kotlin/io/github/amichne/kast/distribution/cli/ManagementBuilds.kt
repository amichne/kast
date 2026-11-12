package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

@Serializable
internal sealed interface VersionsOutcome {
    @Serializable
    @SerialName("LISTED")
    data class Listed(
        val stable: String,
        val main: List<MainBuildEntry>,
        val preview: List<ProductBuildEntry> = emptyList(),
    ) : VersionsOutcome

    @Serializable @SerialName("REJECTED") data class Rejected(val reason: BuildSelectionFailure) : VersionsOutcome
}

internal fun selectableBuilds(read: (String) -> ReleaseDocumentRead = ::readReleaseDocument): VersionsOutcome {
    val stable =
        when (val result = latestStable(read)) {
            is SelectionAdmission.Admitted -> result.value
            is SelectionAdmission.Rejected -> return VersionsOutcome.Rejected(result.reason)
        }
    return when (val catalog = captureMainCatalog(read)) {
        is SelectionAdmission.Admitted -> VersionsOutcome.Listed(stable.value, emptyList(), catalog.value.products)
        is SelectionAdmission.Rejected -> VersionsOutcome.Rejected(catalog.reason)
    }
}

internal fun printSelectableBuilds(json: Boolean, output: ManagementOutput = ManagementOutput()) {
    val outcome = selectableBuilds()
    if (output.structured(json)) output.publish(managementJson.encodeToString(outcome))
    else
        when (outcome) {
            is VersionsOutcome.Listed -> println(selectableBuildLines(outcome).joinToString("\n"))
            is VersionsOutcome.Rejected -> System.err.println("kast: versions: ${outcome.reason}")
        }
    if (outcome is VersionsOutcome.Rejected) kotlin.system.exitProcess(1)
}

@Serializable
internal enum class StableSelectionPolicy {
    TrackStable,
    PinnedBuild,
}

@Serializable
internal enum class ProductSelectionPolicy {
    TrackStable,
    TrackMain,
    PinnedBuild,
}

@Serializable
internal data class PublicReleaseMapping(
    val schemaVersion: Int,
    val publicVersion: String,
    val buildRecordSha256: String,
)

@Serializable
internal sealed interface ResolvedBuildSelection {
    @Serializable
    @SerialName("Stable")
    data class Stable(val version: String, val releaseTag: String, val policy: StableSelectionPolicy) :
        ResolvedBuildSelection

    @Serializable
    @SerialName("Main")
    data class Main(val identity: InstalledBuildIdentity, val policy: UpdatePolicy, val catalogEntry: MainBuildEntry) :
        ResolvedBuildSelection

    @Serializable
    @SerialName("Product")
    data class Product(
        val releaseTag: String,
        val record: ProductBuildRecord,
        val recordSha256: String,
        val policy: ProductSelectionPolicy,
        val publicVersion: String?,
        val mainRevision: String?,
    ) : ResolvedBuildSelection
}

internal fun resolveBuildSelection(
    request: BuildRequest,
    read: (String) -> ReleaseDocumentRead = ::readReleaseDocument,
): SelectionAdmission<ResolvedBuildSelection> =
    when (request) {
        BuildRequest.Stable ->
            when (val version = latestStable(read)) {
                is SelectionAdmission.Admitted -> stableProductSelection(request, version.value, read)
                is SelectionAdmission.Rejected -> version
            }
        is BuildRequest.Version -> stableProductSelection(request, request.version, read)
        BuildRequest.Main,
        is BuildRequest.Commit -> previewProductSelection(request, read)
        BuildRequest.Repair,
        BuildRequest.RecordedPolicy -> SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
    }

private fun previewProductSelection(
    request: BuildRequest,
    read: (String) -> ReleaseDocumentRead,
): SelectionAdmission<ResolvedBuildSelection> =
    when (val catalog = captureMainCatalog(read)) {
        is SelectionAdmission.Rejected -> catalog
        is SelectionAdmission.Admitted ->
            when (val selected = catalog.value.selectProduct(request)) {
                is SelectionAdmission.Rejected -> selected
                is SelectionAdmission.Admitted -> {
                    val entry = selected.value
                    SelectionAdmission.Admitted(
                        ResolvedBuildSelection.Product(
                            entry.releaseTag,
                            entry.record,
                            entry.recordSha256,
                            if (request == BuildRequest.Main) ProductSelectionPolicy.TrackMain
                            else ProductSelectionPolicy.PinnedBuild,
                            null,
                            entry.mainRevision,
                        )
                    )
                }
            }
    }

private fun stableProductSelection(
    request: BuildRequest,
    version: StableVersion,
    read: (String) -> ReleaseDocumentRead,
): SelectionAdmission<ResolvedBuildSelection> {
    val base = "https://github.com/amichne/kast/releases/download/v${version.value}"
    val mappingRaw =
        when (val result = read("$base/release-mapping.json")) {
            is ReleaseDocumentRead.Read -> result.text
            is ReleaseDocumentRead.Unavailable ->
                return SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE)
        }
    val recordRaw =
        when (val result = read("$base/build-record.json")) {
            is ReleaseDocumentRead.Read -> result.text
            is ReleaseDocumentRead.Unavailable ->
                return SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE)
        }
    if (mappingRaw.length > MAX_PRODUCT_RECORD_CHARACTERS || recordRaw.length > MAX_PRODUCT_RECORD_CHARACTERS)
        return SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
    return try {
        val mapping = managementJson.decodeFromString<PublicReleaseMapping>(mappingRaw)
        val record = managementJson.decodeFromString<ProductBuildRecord>(recordRaw)
        val digest = MessageDigest.getInstance("SHA-256").digest(recordRaw.toByteArray()).toHexString()
        if (!validProductRecord(record)) return SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
        if (mapping.schemaVersion != 1 || mapping.publicVersion != version.value || mapping.buildRecordSha256 != digest)
            SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
        else
            SelectionAdmission.Admitted(
                ResolvedBuildSelection.Product(
                    "v${version.value}",
                    record,
                    digest,
                    if (request == BuildRequest.Stable) ProductSelectionPolicy.TrackStable
                    else ProductSelectionPolicy.PinnedBuild,
                    version.value,
                    null,
                )
            )
    } catch (_: SerializationException) {
        SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
    }
}

private const val DISPLAYED_REVISION_LENGTH = 12
private const val MAX_PRODUCT_RECORD_CHARACTERS = 65_536

private fun selectableBuildLines(outcome: VersionsOutcome.Listed): List<String> =
    listOf("stable ${outcome.stable}") +
        outcome.preview.map { entry ->
            val prefix = entry.mainRevision.take(DISPLAYED_REVISION_LENGTH)
            val displayed =
                if (outcome.preview.count { it.mainRevision.startsWith(prefix) } == 1) prefix else entry.mainRevision
            "preview $displayed ${entry.record.version} ${entry.record.targets.joinToString(",") { it.target }}"
        }
