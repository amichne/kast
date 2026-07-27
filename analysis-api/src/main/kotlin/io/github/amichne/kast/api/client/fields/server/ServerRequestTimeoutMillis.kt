package io.github.amichne.kast.api.client.fields

data class ServerRequestTimeoutMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "server"
    override val key: String get() = "requestTimeoutMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(30_000L)
}
