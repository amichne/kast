package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SchemaObject(val type: String = "object")

@Serializable
internal data class SchemaString(val type: String = "string")

@Serializable
internal data class SchemaNonEmptyString(val minLength: Int = 1, val type: String = "string")

@Serializable
internal data class SchemaPatternString(val pattern: String, val type: String = "string")

@Serializable
internal data class SchemaFormatString(val format: String, val type: String = "string")

@Serializable
internal data class SchemaMinimumInteger(val minimum: Int, val type: String = "integer")

@Serializable
internal data class SchemaStringArray(
    val items: SchemaString = SchemaString(),
    val type: String = "array",
)

@Serializable
internal data class SchemaNonEmptyStringArray(
    val items: SchemaString = SchemaString(),
    val minItems: Int = 1,
    val type: String = "array",
)

@Serializable
internal data class SchemaUniqueStringArray(
    val items: SchemaString = SchemaString(),
    val type: String = "array",
    val uniqueItems: Boolean = true,
)

@Serializable
internal data class SchemaObjectArray(
    val items: SchemaObject = SchemaObject(),
    val type: String = "array",
)

@Serializable
internal data class SchemaNonEmptyObjectArray(
    val items: SchemaObject = SchemaObject(),
    val minItems: Int = 1,
    val type: String = "array",
)

@Serializable
internal data class SchemaReference(@SerialName("\$ref") val reference: String)

@Serializable
internal data class SchemaNonEmptyReferenceArray(
    val items: SchemaReference,
    val minItems: Int = 1,
    val type: String = "array",
)

@Serializable
internal data class SchemaIntegerConst(val const: Int)

@Serializable
internal data class SchemaStringConst(val const: String)

@Serializable
internal data class SchemaBooleanConst(val const: Boolean)

@Serializable
internal data class SchemaPatternStringMap(
    val additionalProperties: SchemaPatternString,
    val type: String = "object",
)

@Serializable
internal data class SchemaStringMap(
    val additionalProperties: SchemaString = SchemaString(),
    val type: String = "object",
)

@Serializable
internal data class SchemaNonEmptyStringMap(
    val additionalProperties: SchemaString = SchemaString(),
    val minProperties: Int = 1,
    val type: String = "object",
)
