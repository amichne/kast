package io.github.amichne.kast.api.client.fields

data class RuntimeDefaultBackend(
    override val value: String,
) : ConfigurationField<String>() {
    init {
        require(value in allowedValues) {
            "runtime.defaultBackend must be one of ${allowedValues.joinToString(", ")}"
        }
    }

    override val section: String get() = "runtime"
    override val key: String get() = "defaultBackend"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("auto")

    companion object {
        private val allowedValues = setOf("auto", "headless", "idea")
    }
}
