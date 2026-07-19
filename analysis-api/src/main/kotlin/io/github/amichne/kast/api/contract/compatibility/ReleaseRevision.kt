package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ReleaseRevision(
    @DocField(description = "Full source revision that produced a released CLI or plugin artifact.")
    val value: String,
) {
    init {
        require(
            value.length == REVISION_LENGTH &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' },
        ) {
            "Release revision must be a full 40-character lowercase hexadecimal Git revision"
        }
    }

    override fun toString(): String = value

    private companion object {
        const val REVISION_LENGTH = 40
    }
}
