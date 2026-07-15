package io.github.amichne.kast.idea

@JvmInline
value class PluginVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "Kast plugin version must not be blank" }
        require(value != "unknown") { "Kast plugin version must be explicit" }
    }
}
