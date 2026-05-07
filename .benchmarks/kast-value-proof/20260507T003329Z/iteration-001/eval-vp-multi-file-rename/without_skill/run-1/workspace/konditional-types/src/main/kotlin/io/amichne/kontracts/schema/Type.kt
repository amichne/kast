package io.amichne.kontracts.schema

/**
 * JSON Schema data types, as per OpenAPI Specification.
 *
 * @property serialized The serialized string representation of the type.
 */
enum class Type(val serialized: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object"),
    NULL("null")
}
