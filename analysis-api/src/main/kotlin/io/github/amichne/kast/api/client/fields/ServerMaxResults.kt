package io.github.amichne.kast.api.client.fields

data class ServerMaxResults(
    override val value: Int,
) : ConfigurationField<Int>() {
    override val section: String get() = "server"
    override val key: String get() = "maxResults"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(500)
}
