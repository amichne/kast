package io.github.amichne.kast.api.client.fields

@JvmInline
value class ConfigurationDefault<T>(val value: T) {
    val unwrap: T get() = value
}
