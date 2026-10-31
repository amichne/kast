package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface UpdatePolicy {
    @Serializable @SerialName("TrackStable") data object TrackStable : UpdatePolicy

    @Serializable @SerialName("TrackMain") data object TrackMain : UpdatePolicy

    @Serializable @SerialName("PinnedBuild") data class PinnedBuild(val identity: InstalledBuildIdentity) : UpdatePolicy
}

/** Source provenance stays distinct from compatibility and the selected main revision. */
@Serializable
data class InstalledBuildIdentity(
    val releaseTag: String,
    val version: String,
    val buildRevision: String,
    val sourceTree: String,
    val mainRevision: String? = null,
) {
    init {
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
        require(releaseTag == "v$version" || releaseTag == "developer-v$version")
        require(
            listOfNotNull(buildRevision, sourceTree, mainRevision).all {
                Regex("[0-9a-f]{40}").matches(it) && it != "0".repeat(SOURCE_REVISION_LENGTH)
            }
        )
        require((mainRevision == null) == releaseTag.startsWith("v"))
    }
}

@Serializable
data class ManagementBuildBinding(val identity: InstalledBuildIdentity, val policy: UpdatePolicy) {
    init {
        require(
            when (policy) {
                UpdatePolicy.TrackStable -> identity.mainRevision == null
                UpdatePolicy.TrackMain -> identity.mainRevision != null
                is UpdatePolicy.PinnedBuild -> policy.identity == identity
            }
        )
    }
}

private const val SOURCE_REVISION_LENGTH = 40
