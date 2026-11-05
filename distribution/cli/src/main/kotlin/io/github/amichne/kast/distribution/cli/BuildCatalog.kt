package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable internal data class BuildRecordReference(val name: String, val sha256: String)

@Serializable internal data class MainBuildCatalog(val schemaVersion: Int, val builds: List<LegacyMainBuildEntry>)

@Serializable
internal data class VersionedMainBuildCatalog(val schemaVersion: Int, val builds: List<VersionedMainBuildEntry>)

/** A captured discovery snapshot never selects a different entry after platform rejection. */
internal class AdmittedBuildCatalog private constructor(entries: List<MainBuildEntry>) {
    val entries: List<MainBuildEntry> = java.util.List.copyOf(entries)

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
        fun decodeV2(raw: String): SelectionAdmission<AdmittedBuildCatalog> {
            if (raw.length > MAX_CATALOG_CHARACTERS)
                return SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
            val document =
                try {
                    managementJson.decodeFromString<VersionedMainBuildCatalog>(raw)
                } catch (_: SerializationException) {
                    return SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
                }
            if (
                document.schemaVersion != 2 ||
                    document.builds.size > MAX_CATALOG_ENTRIES ||
                    !validEntries(document.builds) ||
                    !document.builds.all(::validTargets)
            )
                return SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
            return SelectionAdmission.Admitted(
                AdmittedBuildCatalog(
                    document.builds.map { entry ->
                        entry.copy(
                            platformTargets = java.util.List.copyOf(entry.platformTargets),
                            hostTargets = java.util.List.copyOf(entry.hostTargets),
                        )
                    }
                )
            )
        }

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
            return if (validCatalog(document))
                SelectionAdmission.Admitted(
                    AdmittedBuildCatalog(
                        entries.map { it.copy(platformTargets = java.util.List.copyOf(it.platformTargets)) }
                    )
                )
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
        entry.candidate.name == (if (entry is VersionedMainBuildEntry) "candidate-v2.json" else "candidate.json") &&
        entry.promotion.name == (if (entry is VersionedMainBuildEntry) "promotion-v2.json" else "promotion.json") &&
        listOf(entry.candidate, entry.promotion).all { Regex("[0-9a-f]{64}").matches(it.sha256) }

private const val MAX_CATALOG_CHARACTERS = 65_536
private const val MAX_CATALOG_ENTRIES = 5
private const val REVISION_LENGTH = 40

private fun validCatalog(document: MainBuildCatalog): Boolean =
    document.schemaVersion == 1 && document.builds.size <= MAX_CATALOG_ENTRIES && validEntries(document.builds)

private fun validEntries(entries: List<MainBuildEntry>): Boolean =
    entries.map { it.mainRevision }.distinct().size == entries.size && entries.all(::validEntry)

/** Historic entries retain their own compile pins; the selected record later binds them to that source revision. */
private fun validTargets(entry: VersionedMainBuildEntry): Boolean {
    if (entry.hostTargets.map { it.intellijReleaseLine } != listOf("262", "263")) return false
    return entry.hostTargets.all { target ->
        target.ideBuild.matches(Regex("${target.intellijReleaseLine}\\.[0-9]+\\.[0-9]+")) &&
            target.kotlinPluginBuild == "${target.ideBuild}-IJ" &&
            target.record.name ==
                "kast-host-release-v${entry.version}" +
                    (if (target.intellijReleaseLine == "262") "" else "-idea-${target.intellijReleaseLine}") +
                    ".json" &&
            Regex("[0-9a-f]{64}").matches(target.record.sha256)
    }
}
