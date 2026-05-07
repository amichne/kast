package io.amichne.konditional.core

/**
 * Enum representing the supported value types for feature flags.
 */
enum class ValueType {
    BOOLEAN,
    STRING,
    INT,
    LONG,
    DOUBLE,
    JSON,
    ENUM,
    JSON_OBJECT,
    JSON_ARRAY,
    DATA_CLASS,
}
