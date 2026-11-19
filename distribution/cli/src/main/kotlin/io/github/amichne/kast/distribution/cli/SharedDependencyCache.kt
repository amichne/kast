package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

internal fun prepareSharedDependencyCache(home: Path, staging: Path, localSeed: Path?) =
    io.github.amichne.kast.distribution.managed.prepareSharedDependencyCache(
        home,
        staging,
        localSeed,
        MAXIMUM_PROFILE_FILES,
    ) { failure ->
        recordContainerFilesystemObservation(containerFilesystemObservation(failure))
    }
