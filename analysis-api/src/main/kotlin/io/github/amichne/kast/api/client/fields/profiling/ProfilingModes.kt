package io.github.amichne.kast.api.client.fields

data class ProfilingModes(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "profiling"
    override val key: String get() = "modes"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("cpu")

    /**
     * Proof transition: `ProfilingModes -> ProfilingModeResolution`.
     *
     * Establishes a non-empty, canonical supported mode set or returns the
     * closed [ProfilingModeFailure] that prevented admission. Raw comma-
     * separated text is extracted only at the runtime configuration boundary.
     */
    fun resolve(): ProfilingModeResolution {
        val requested = value.split(',').map(String::trim)
        if (requested.any(String::isEmpty)) {
            return ProfilingModeResolution.Rejected(ProfilingModeFailure.Empty)
        }
        val supported = linkedSetOf<ProfilingMode>()
        val unsupported = linkedSetOf<String>()
        requested.forEach { rawMode ->
            val normalized = rawMode.lowercase()
            val mode = ProfilingMode.entries.firstOrNull { normalized in it.aliases }
            if (mode == null) unsupported += rawMode else supported += mode
        }
        return if (unsupported.isEmpty()) {
            ProfilingModeResolution.Resolved(ProfilingModeSelection.of(supported))
        } else {
            ProfilingModeResolution.Rejected(ProfilingModeFailure.Unsupported(unsupported))
        }
    }
}

enum class ProfilingMode(
    val wireName: String,
    internal val aliases: Set<String>,
) {
    CPU("cpu", setOf("cpu")),
    ALLOCATION("allocation", setOf("alloc", "allocation")),
    LOCK("lock", setOf("lock")),
    WALL("wall", setOf("wall")),
}

class ProfilingModeSelection private constructor(
    val modes: Set<ProfilingMode>,
) {
    init {
        require(modes.isNotEmpty()) { "Profiling mode selection must not be empty" }
    }

    companion object {
        /**
         * Proof transition: `Set<ProfilingMode> -> ProfilingModeSelection`.
         *
         * Preserves an already-admitted non-empty mode set in canonical order.
         * Raw set extraction is permitted only at profiler configuration.
         */
        internal fun of(modes: Set<ProfilingMode>): ProfilingModeSelection =
            ProfilingModeSelection(ProfilingMode.entries.filterTo(linkedSetOf()) { it in modes })
    }
}

sealed interface ProfilingModeResolution {
    data class Resolved(val selection: ProfilingModeSelection) : ProfilingModeResolution

    data class Rejected(val failure: ProfilingModeFailure) : ProfilingModeResolution
}

sealed interface ProfilingModeFailure {
    data object Empty : ProfilingModeFailure

    data class Unsupported(val values: Set<String>) : ProfilingModeFailure {
        init {
            require(values.isNotEmpty()) { "Unsupported profiling modes must not be empty" }
        }
    }
}
