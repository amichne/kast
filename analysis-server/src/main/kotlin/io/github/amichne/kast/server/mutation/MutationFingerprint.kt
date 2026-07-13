package io.github.amichne.kast.server.mutation

@JvmInline
internal value class MutationFingerprint(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Mutation fingerprint must not be blank" }
    }
}
