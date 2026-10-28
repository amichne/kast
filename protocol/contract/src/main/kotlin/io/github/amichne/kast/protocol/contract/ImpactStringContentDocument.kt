package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

@Serializable
enum class ImpactStringContentInputDocument {
    STRING,
    NULLABLE_STRING,
}

@Serializable
enum class ImpactStringNullAlternativeDocument {
    NOT_NULLABLE,
    CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT,
}
