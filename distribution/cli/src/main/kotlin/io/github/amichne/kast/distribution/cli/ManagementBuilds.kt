package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal sealed interface VersionsOutcome {
    @Serializable
    @SerialName("LISTED")
    data class Listed(val stable: String, val main: List<MainBuildEntry>) : VersionsOutcome

    @Serializable @SerialName("REJECTED") data class Rejected(val reason: BuildSelectionFailure) : VersionsOutcome
}

internal fun selectableBuilds(read: (String) -> ReleaseDocumentRead = ::readReleaseDocument): VersionsOutcome {
    val stable =
        when (val result = latestStable(read)) {
            is SelectionAdmission.Admitted -> result.value
            is SelectionAdmission.Rejected -> return VersionsOutcome.Rejected(result.reason)
        }
    return when (val catalog = captureMainCatalog(read)) {
        is SelectionAdmission.Admitted -> VersionsOutcome.Listed(stable.value, catalog.value.entries)
        is SelectionAdmission.Rejected -> VersionsOutcome.Rejected(catalog.reason)
    }
}

internal fun printSelectableBuilds(json: Boolean) {
    val outcome = selectableBuilds()
    if (json) println(managementJson.encodeToString(outcome))
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
internal sealed interface ResolvedBuildSelection {
    @Serializable
    @SerialName("Stable")
    data class Stable(val version: String, val releaseTag: String, val policy: StableSelectionPolicy) :
        ResolvedBuildSelection

    @Serializable
    @SerialName("Main")
    data class Main(val identity: InstalledBuildIdentity, val policy: UpdatePolicy, val catalogEntry: MainBuildEntry) :
        ResolvedBuildSelection
}

internal fun resolveBuildSelection(
    request: BuildRequest,
    read: (String) -> ReleaseDocumentRead = ::readReleaseDocument,
): SelectionAdmission<ResolvedBuildSelection> =
    when (request) {
        BuildRequest.Stable ->
            when (val version = latestStable(read)) {
                is SelectionAdmission.Admitted -> stableSelection(request, version.value)
                is SelectionAdmission.Rejected -> version
            }
        is BuildRequest.Version -> stableSelection(request, request.version)
        BuildRequest.Main,
        is BuildRequest.Commit ->
            when (val catalog = captureMainCatalog(read)) {
                is SelectionAdmission.Rejected -> catalog
                is SelectionAdmission.Admitted ->
                    when (val selected = catalog.value.select(request, "macos-aarch64")) {
                        is SelectionAdmission.Rejected -> selected
                        is SelectionAdmission.Admitted -> {
                            val entry = selected.value
                            val identity =
                                InstalledBuildIdentity(
                                    entry.releaseTag,
                                    entry.version,
                                    entry.buildRevision,
                                    entry.sourceTree,
                                    entry.mainRevision,
                                )
                            SelectionAdmission.Admitted(
                                ResolvedBuildSelection.Main(identity, updatePolicy(request, identity), entry)
                            )
                        }
                    }
            }
        BuildRequest.RecordedPolicy -> SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
    }

private fun stableSelection(request: BuildRequest, version: StableVersion): SelectionAdmission<ResolvedBuildSelection> {
    // Stable artifact admission supplies provenance before installation effects.
    return SelectionAdmission.Admitted(
        ResolvedBuildSelection.Stable(
            version.value,
            "v${version.value}",
            if (request == BuildRequest.Stable) StableSelectionPolicy.TrackStable
            else StableSelectionPolicy.PinnedBuild,
        )
    )
}

private const val DISPLAYED_REVISION_LENGTH = 12

private fun selectableBuildLines(outcome: VersionsOutcome.Listed): List<String> =
    listOf("stable ${outcome.stable}") +
        outcome.main.map { entry ->
            val prefix = entry.mainRevision.take(DISPLAYED_REVISION_LENGTH)
            val displayed =
                if (outcome.main.count { it.mainRevision.startsWith(prefix) } == 1) prefix else entry.mainRevision
            "main $displayed ${entry.version} ${entry.platformTargets.joinToString(",")}"
        }
