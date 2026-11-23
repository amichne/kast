package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

/**
 * The profile of one private workspace container: its directory beneath `<store>/containers`, or one generation of it.
 * A profile is not an installation. It holds its own `config` and `state`, no payload, and no lifecycle records.
 */
@JvmInline
value class ContainerProfileRoot private constructor(val path: Path) {
    /** The configuration file that `KAST_CONFIGURATION_FILE` names inside the container. */
    val configuration: Path
        get() = path.resolve("config/environment")

    /** The Host launch this profile selected. */
    val selectedIde: Path
        get() = path.resolve("config/selected-ide.json")

    companion object {
        /** The profile at [path], as admitted by the caller. */
        fun of(path: Path): ContainerProfileRoot = ContainerProfileRoot(path)

        /** The profile that [configuration] names, or null when its parent directory has no parent. */
        fun ofConfigurationFile(configuration: Path): ContainerProfileRoot? =
            configuration.parent.parent?.let(::ContainerProfileRoot)
    }
}
