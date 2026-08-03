package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RawExactFileObservationPath private constructor(
    @DocField(description = "Canonical normalized path relative to the exact workspace root.")
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Raw exact-file observation path must not be blank" }
        require(value.none(Char::isISOControl)) {
            "Raw exact-file observation path must not contain control characters"
        }
        require('\\' !in value) {
            "Raw exact-file observation path must use forward slashes"
        }
        require(!value.startsWith('/')) {
            "Raw exact-file observation path must be workspace-relative"
        }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(value)) {
            "Raw exact-file observation path must not use a Windows drive prefix"
        }
        require(value.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }) {
            "Raw exact-file observation path must be normalized and contained"
        }
    }

    companion object {
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")

        fun parse(value: String): RawExactFileObservationPath = RawExactFileObservationPath(value)
    }
}
