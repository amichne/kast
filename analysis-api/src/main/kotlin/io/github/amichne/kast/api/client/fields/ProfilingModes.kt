package io.github.amichne.kast.api.client.fields

data class ProfilingModes(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "profiling"
    override val key: String get() = "modes"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("cpu")

    fun parseModes(): Set<ProfilingMode> = value
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull(ProfilingMode::parse)
        .toSet()
}

enum class ProfilingMode(private val aliases: Set<String>) {
    CPU(setOf("cpu")),
    ALLOCATION(setOf("alloc", "allocation")),
    LOCK(setOf("lock")),
    WALL(setOf("wall"));

    companion object {
        fun parse(value: String): ProfilingMode? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { normalized in it.aliases }
        }
    }
}
