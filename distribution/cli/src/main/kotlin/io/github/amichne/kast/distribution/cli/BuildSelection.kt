package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import kotlinx.serialization.Serializable

@Serializable
internal enum class BuildSelectionFailure {
    SELECTORS_CONFLICT,
    SELECTOR_INVALID,
    AMBIGUOUS_COMMIT,
    PINNED_BUILD,
    BINARY_UNAVAILABLE,
    PLATFORM_UNAVAILABLE,
    CATALOG_UNAVAILABLE,
    ARTIFACT_INVALID,
    INSTALLER_CONTRACT_UNSUPPORTED,
}

internal sealed interface SelectionAdmission<out Value> {
    data class Admitted<Value>(val value: Value) : SelectionAdmission<Value>

    data class Rejected(val reason: BuildSelectionFailure) : SelectionAdmission<Nothing>
}

@Serializable
internal class StableVersion private constructor(val value: String) {
    init {
        require(PATTERN.matches(value))
    }

    override fun equals(other: Any?): Boolean = other is StableVersion && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        private val PATTERN = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")

        fun parse(raw: String): SelectionAdmission<StableVersion> =
            if (raw.length <= MAX_VERSION_LENGTH && PATTERN.matches(raw))
                SelectionAdmission.Admitted(StableVersion(raw))
            else SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
    }
}

internal class MainSelector private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is MainSelector && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun parse(raw: String): SelectionAdmission<MainSelector> =
            if (validSelectorLength(raw) && raw.all { it in '0'..'9' || it in 'a'..'f' })
                SelectionAdmission.Admitted(MainSelector(raw))
            else SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
    }
}

internal sealed interface BuildRequest {
    data object RecordedPolicy : BuildRequest

    data object Stable : BuildRequest

    data object Main : BuildRequest

    data class Version(val version: StableVersion) : BuildRequest

    data class Commit(val selector: MainSelector) : BuildRequest
}

internal fun admitBuildRequest(channel: String?, version: String?, commit: String?): SelectionAdmission<BuildRequest> {
    if (listOfNotNull(channel, version, commit).size > 1)
        return SelectionAdmission.Rejected(BuildSelectionFailure.SELECTORS_CONFLICT)
    return when {
        channel == "stable" -> SelectionAdmission.Admitted(BuildRequest.Stable)
        channel == "main" -> SelectionAdmission.Admitted(BuildRequest.Main)
        channel != null -> SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID)
        version != null ->
            when (val parsed = StableVersion.parse(version)) {
                is SelectionAdmission.Admitted -> SelectionAdmission.Admitted(BuildRequest.Version(parsed.value))
                is SelectionAdmission.Rejected -> parsed
            }
        commit != null ->
            when (val parsed = MainSelector.parse(commit)) {
                is SelectionAdmission.Admitted -> SelectionAdmission.Admitted(BuildRequest.Commit(parsed.value))
                is SelectionAdmission.Rejected -> parsed
            }
        else -> SelectionAdmission.Admitted(BuildRequest.RecordedPolicy)
    }
}

internal fun resolveRecordedPolicy(request: BuildRequest, policy: UpdatePolicy): SelectionAdmission<BuildRequest> =
    when (request) {
        BuildRequest.RecordedPolicy ->
            when (policy) {
                UpdatePolicy.TrackStable -> SelectionAdmission.Admitted(BuildRequest.Stable)
                UpdatePolicy.TrackMain -> SelectionAdmission.Admitted(BuildRequest.Main)
                is UpdatePolicy.PinnedBuild -> SelectionAdmission.Rejected(BuildSelectionFailure.PINNED_BUILD)
            }
        BuildRequest.Stable,
        BuildRequest.Main,
        is BuildRequest.Version,
        is BuildRequest.Commit -> SelectionAdmission.Admitted(request)
    }

internal fun updatePolicy(request: BuildRequest, identity: InstalledBuildIdentity): UpdatePolicy =
    when (request) {
        BuildRequest.Stable -> UpdatePolicy.TrackStable
        BuildRequest.Main -> UpdatePolicy.TrackMain
        is BuildRequest.Version,
        is BuildRequest.Commit -> UpdatePolicy.PinnedBuild(identity)
        BuildRequest.RecordedPolicy -> error("Resolve the recorded policy before artifact selection")
    }

private const val MAX_VERSION_LENGTH = 64
private const val DISPLAY_REVISION_LENGTH = 12
private const val FULL_REVISION_LENGTH = 40

private fun validSelectorLength(raw: String): Boolean =
    raw.length == DISPLAY_REVISION_LENGTH || raw.length == FULL_REVISION_LENGTH
