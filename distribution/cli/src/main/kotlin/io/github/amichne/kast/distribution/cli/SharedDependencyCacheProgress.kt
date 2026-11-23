package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.SharedDependencyCacheOutcome
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class SharedDependencyCacheProgress(
    val result: SharedDependencyCacheOutcome,
    val event: String = "kast_dependency_cache",
)

/** A cold workspace without the read-only copy downloads every dependency, so a skipped copy is never silent. */
internal fun prepareSharedDependencyCache(
    home: Path,
    staging: Path,
    localSeed: Path?,
    observe: (SharedDependencyCacheProgress) -> Unit = { System.err.println(containerJson.encodeToString(it)) },
) {
    val outcome =
        io.github.amichne.kast.distribution.managed.prepareSharedDependencyCache(
            home,
            staging,
            localSeed,
            MAXIMUM_PROFILE_FILES,
        ) { failure ->
            recordContainerFilesystemObservation(containerFilesystemObservation(failure))
        }
    if (outcome != SharedDependencyCacheOutcome.AlreadyPublished) observe(SharedDependencyCacheProgress(outcome))
}
