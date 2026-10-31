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

/** A captured discovery snapshot never selects a different entry after platform rejection. */
internal class AdmittedBuildCatalog private constructor(val entries: List<MainBuildEntry>) {
    fun select(request: BuildRequest, platform: String): SelectionAdmission<MainBuildEntry> {
        val matches =
            when (request) {
                BuildRequest.Main -> entries.take(1)
                is BuildRequest.Commit -> entries.filter { it.mainRevision.startsWith(request.selector.value) }
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

private fun validCatalog(document: MainBuildCatalog): Boolean =
    document.schemaVersion == 1 && document.builds.size <= MAX_CATALOG_ENTRIES && validEntries(document.builds)

private fun validEntries(entries: List<MainBuildEntry>): Boolean =
    entries.map { it.mainRevision }.distinct().size == entries.size && entries.all(::validEntry)
