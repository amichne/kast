@file:OptIn(io.amichne.konditional.api.KonditionalInternalApi::class)
@file:Suppress("TooManyFunctions")

package io.amichne.konditional.serialization

import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.parseFailure
import io.amichne.konditional.core.types.Konstrained
import io.amichne.kontracts.dsl.jsonArray
import io.amichne.kontracts.dsl.jsonObject
import io.amichne.kontracts.dsl.jsonValue
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.ValidationResult
import io.amichne.kontracts.value.JsonArray
import io.amichne.kontracts.value.JsonBoolean
import io.amichne.kontracts.value.JsonNull
import io.amichne.kontracts.value.JsonNumber
import io.amichne.kontracts.value.JsonObject
import io.amichne.kontracts.value.JsonString
import io.amichne.kontracts.value.JsonValue
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Schema-based serializer that uses reflection and ObjectSchema to encode/decode instances.
 *
 * Supports all [Konstrained] variants:
 * - [Konstrained.Object]: data-class encoding via field reflection
 * - [Konstrained.Primitive.String], [Konstrained.Primitive.Boolean], [Konstrained.Primitive.Int],
 *   [Konstrained.Primitive.Double]: single-property primitive extraction
 * - [Konstrained.Array]: single-property list extraction
 * - [Konstrained.AsString], [Konstrained.AsInt], [Konstrained.AsBoolean], [Konstrained.AsDouble]:
 *   adapted primitive encoding via explicit instance codecs
 *
 * Any type implementing [Konstrained] can be encoded/decoded without additional registration.
 * For primitive and array-backed values the implementing class must have exactly one property of the
 * matching Kotlin type; `@JvmInline value class` is the idiomatic way to guarantee this at the
 * language level.
 */
internal enum class SingletonUnknownFieldMode {
    REJECT_UNKNOWN_FIELDS,
    IGNORE_UNKNOWN_FIELDS,
}

@Suppress("TooManyFunctions")
internal object SchemaValueCodec {

    /**
     * Encodes a value to JsonObject using its schema.
     *
     * @throws IllegalStateException if a property referenced by the schema is missing.
     */
    fun <T : Any> encode(value: T, schema: ObjectSchema): JsonObject {
        val fields =
            schema.fields.mapValues { (fieldName, _) ->
                val property =
                    value::class.memberProperties.find { it.name == fieldName }
                        ?: error("Property '$fieldName' not found on ${value::class.qualifiedName}")

                val propertyValue = property.call(value)

                when {
                    propertyValue == null -> JsonNull
                    else -> encodeValue(propertyValue)
                }
            }

        return jsonObject {
            this.schema = schema
            fields(fields)
        }
    }

    /**
     * Encodes any [Konstrained] instance to the appropriate [JsonValue] by dispatching on
     * its runtime type and declared schema.
     *
     * Dispatch order:
     * 1. [Konstrained.AsString] / [Konstrained.AsInt] / [Konstrained.AsBoolean] /
     *    [Konstrained.AsDouble] — calls the instance's [Konstrained.AsString.encode] method,
     *    enabling domain types that are not themselves JSON primitives (e.g. `LocalDate`).
     *    Checked first so that an `AsString<LocalDate>` (whose `schema` IS a `StringSchema`)
     *    does not accidentally fall into the [Konstrained.Primitive.String] path.
     * 2. Object schemas → [JsonObject] (via field-reflection codec)
     * 3. String/Boolean/Int/Double schemas → the matching JSON primitive (existing path,
     *    for [Konstrained.Primitive] types whose value IS the primitive).
     * 4. Array schemas → [JsonArray] from the single list-typed property.
     *
     * @throws IllegalArgumentException if the schema type is unsupported, or if the
     *   implementing class does not have the required single-property structure for
     *   primitive/array schemas.
     */
    fun encodeKonstrained(konstrained: Konstrained): JsonValue =
        when {
            konstrained is Konstrained.AsString<*, *> -> jsonValue { string(konstrained.encode()) }
            konstrained is Konstrained.AsInt<*, *> -> jsonValue { number(konstrained.encode()) }
            konstrained is Konstrained.AsBoolean<*, *> -> jsonValue { boolean(konstrained.encode()) }
            konstrained is Konstrained.AsDouble<*, *> -> jsonValue { number(konstrained.encode()) }
            konstrained is Konstrained.Object -> encode(konstrained, extractObjectSchema(konstrained))
            konstrained is Konstrained.Primitive.String ->
                jsonValue { string(konstrained.extractSinglePrimitiveProperty()) }
            konstrained is Konstrained.Primitive.Boolean ->
                jsonValue { boolean(konstrained.extractSinglePrimitiveProperty()) }
            konstrained is Konstrained.Primitive.Int ->
                jsonValue { number(konstrained.extractSinglePrimitiveProperty<Int>()) }
            konstrained is Konstrained.Primitive.Double ->
                jsonValue { number(konstrained.extractSinglePrimitiveProperty<Double>()) }
            konstrained is Konstrained.Array<*> -> encodeKonstrainedArray(konstrained)
            else ->
                error("Unsupported Konstrained subtype: ${konstrained::class.qualifiedName}")
        }

    /**
     * Decodes a raw primitive or list value back into a [Konstrained] value class instance.
     *
     * ## Dispatch order
     *
     * 1. **Companion [Konstrained.Decoder]** — if the target class has a companion object
     *    that implements [Konstrained.Decoder]`<P, T>` (where `P` matches the runtime type
     *    of [rawValue]), the companion's [Konstrained.Decoder.decode] is called directly.
     *    This is the canonical path for [Konstrained.AsString] / [Konstrained.AsInt] /
     *    [Konstrained.AsBoolean] / [Konstrained.AsDouble] types whose wrapped domain value
     *    is not itself a JSON primitive (e.g. `LocalDate`, `UUID`).
     *
     * 2. **Primary constructor** — fallback for [Konstrained.Primitive] types whose single
     *    constructor parameter type matches [rawValue] directly (e.g. `Email(value: String)`).
     *    The [kClass] must have a primary constructor with exactly one parameter whose type
     *    is assignment-compatible with [rawValue]. `@JvmInline value class` satisfies this by
     *    construction.
     *
     * @param kClass Target class to instantiate (typically a value class).
     * @param rawValue The raw primitive (`String`, `Boolean`, `Int`, `Double`) or `List<*>`.
     * @return [Result.success] with the constructed instance, or [Result.failure] with a
     *   [ParseError.InvalidSnapshot] if construction fails.
     */
    @Suppress("ReturnCount")
    fun <T : Any> decodeKonstrainedPrimitive(kClass: KClass<T>, rawValue: Any): Result<T> {
        // Step 1: prefer a companion Decoder when present — supports As* types with
        // non-primitive domain values. The cast is safe: the companion is the companion
        // of kClass (which produces T), and Decoder's V parameter is covariant.
        val decoderResult = decodeViaDecoder(kClass, rawValue)
        if (decoderResult != null) return decoderResult

        // Step 2: fall back to direct constructor invocation for Konstrained.Primitive types.
        val constructor =
            kClass.primaryConstructor
                ?: return parseFailure(
                    ParseError.InvalidSnapshot(
                        "${kClass.qualifiedName} has no primary constructor",
                    ),
                )

        if (constructor.parameters.size != 1) {
            return parseFailure(
                ParseError.InvalidSnapshot(
                    "${kClass.qualifiedName} must have exactly one constructor parameter for primitive " +
                        "schema backing (got ${constructor.parameters.size}). " +
                        "Consider using @JvmInline value class.",
                ),
            )
        }

        return runCatching { constructor.call(rawValue) }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    parseFailure(
                        ParseError.InvalidSnapshot(
                            "Failed to construct ${kClass.qualifiedName} from $rawValue: ${it.message}",
                        ),
                    )
                },
            )
    }

    /**
     * Attempts to decode [rawValue] via a companion-object [Konstrained.Decoder], returning
     * `null` when no matching companion decoder is found so the caller can fall through to
     * the primary-constructor path.
     *
     * A single [Konstrained.Decoder]`<P, V>` interface replaces the previous four bespoke
     * `StringDecoder` / `IntDecoder` / `BooleanDecoder` / `DoubleDecoder` interfaces.
     * The `P` type parameter is inferred from the runtime type of [rawValue]; the unchecked
     * cast to `Decoder<P, T>` is safe because the companion is the companion of [kClass],
     * which by the [Konstrained.Decoder] contract produces values of type `T`.
     */
    @Suppress("UNCHECKED_CAST", "ReturnCount")

    private fun <T : Any> decodeViaDecoder(
        kClass: KClass<T>,
        rawValue: Any
    ): Result<T>? {
        val companion = kClass.companionObjectInstance ?: return null
        if (companion !is Konstrained.Decoder<*, *>) return null
        val errorMessage: (Throwable) -> String = {
            "Decoder on ${kClass.qualifiedName} failed for value '$rawValue': ${it.message}"
        }
        return when (rawValue) {
            is String ->
                runCatching { (companion as Konstrained.Decoder<String, T>).decode(rawValue) }
                    .fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { parseFailure(ParseError.InvalidSnapshot(errorMessage(it))) },
                    )

            is Int ->
                runCatching { (companion as Konstrained.Decoder<Int, T>).decode(rawValue) }
                    .fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { parseFailure(ParseError.InvalidSnapshot(errorMessage(it))) },
                    )

            is Boolean ->
                runCatching { (companion as Konstrained.Decoder<Boolean, T>).decode(rawValue) }
                    .fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { parseFailure(ParseError.InvalidSnapshot(errorMessage(it))) },
                    )

            is Double ->
                runCatching { (companion as Konstrained.Decoder<Double, T>).decode(rawValue) }
                    .fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { parseFailure(ParseError.InvalidSnapshot(errorMessage(it))) },
                    )

            else -> null
        }
    }

    private fun encodeValue(value: Any): JsonValue =
        when (value) {
            is Boolean -> jsonValue { boolean(value) }
            is String -> jsonValue { string(value) }
            is Int -> jsonValue { number(value) }
            is Double -> jsonValue { number(value) }
            is Enum<*> -> jsonValue { string(value.name) }
            is Konstrained -> encodeKonstrained(value)
            else ->
                error(
                    "Unsupported type for encoding: ${value::class.qualifiedName}. " +
                        "Supported built-in types: Boolean, String, Int, Double, Enum. " +
                        "Custom types must implement Konstrained.",
                )
        }

    private fun extractObjectSchema(konstrained: Konstrained.Object): ObjectSchema =
        extractSchema(konstrained::class)
            ?: error("Cannot extract ObjectSchema from ${konstrained::class.qualifiedName}")

    private fun encodeKonstrainedArray(konstrained: Konstrained.Array<*>): JsonArray {
        val kClass = konstrained::class
        val listProps = kClass.memberProperties.filter { it.returnType.classifier == List::class }
        val prop =
            listProps.singleOrNull()
                ?: error(
                    "${kClass.simpleName} must have exactly one List-typed property for array-backed Konstrained " +
                        "(found ${listProps.size}: ${listProps.map { it.name }}). " +
                        "Consider using @JvmInline value class.",
                )
        val list =
            prop.call(konstrained) as? List<*>
                ?: error(
                    "${kClass.simpleName}.${prop.name} must be a List for array-backed Konstrained.",
                )
        return jsonArray {
            elements(list.map { element -> element.toJsonValue() })
        }
    }

    /**
     * Decodes JsonObject to an instance using schema and reflection.
     *
     * Kotlin `object` singletons have no primary constructor; when [kClass] is a singleton
     * its `objectInstance` is returned only after schema-based payload validation.
     */
    fun <T : Any> decode(
        kClass: KClass<T>,
        json: JsonObject,
        schema: ObjectSchema,
        singletonUnknownFieldMode: SingletonUnknownFieldMode = SingletonUnknownFieldMode.REJECT_UNKNOWN_FIELDS,
    ): Result<T> {
        kClass.objectInstance?.let { singleton ->
            return validateSingletonPayload(
                json = json,
                schema = schema,
                singletonUnknownFieldMode = singletonUnknownFieldMode,
            ).map { singleton }
        }
        return kClass.primaryConstructor
            ?.let { constructor ->
                buildSchemaParameterMap(constructor, json, schema, kClass, ::decodeValue)
                    .fold(
                        onSuccess = { parameters ->
                            runCatching { constructor.callBy(parameters) }
                                .fold(
                                    onSuccess = { Result.success(it) },
                                    onFailure = { error ->
                                        parseFailure(
                                            ParseError.InvalidSnapshot(
                                                "Failed to instantiate ${kClass.qualifiedName}: ${error.message}",
                                            ),
                                        )
                                    },
                                )
                        },
                        onFailure = { error -> Result.failure(error) },
                    )
            }
            ?: parseFailure(
                ParseError.InvalidSnapshot(
                    "${kClass.qualifiedName} must have a primary constructor for deserialization",
                ),
            )
    }

    /**
     * Decodes JsonObject to an instance using an extractable schema if present.
     * Falls back to constructor-based decoding when no schema is available.
     */
    fun <T : Any> decode(kClass: KClass<T>, json: JsonObject): Result<T> =
        extractSchema(kClass)
            ?.let { schema -> decode(kClass, json, schema, SingletonUnknownFieldMode.REJECT_UNKNOWN_FIELDS) }
            ?: decodeWithoutSchema(kClass, json)

    /**
     * Unified decode entry point symmetric with [encodeKonstrained].
     *
     * Dispatches on the runtime type of [jsonValue]:
     * - [JsonObject] → [decode] (handles data classes and Kotlin `object` singletons)
     * - [JsonNull] → [Result.failure] with [ParseError.InvalidSnapshot]
     * - All other [JsonValue] variants → [decodeKonstrainedPrimitive] after extracting the
     *   raw Kotlin primitive via [toKotlinPrimitive].
     *
     * For [JsonNumber], [Konstrained.Primitive.Int] and [Konstrained.AsInt] targets receive
     * an [Int]; all other targets receive a [Double].
     */
    fun <T : Any> decodeKonstrained(kClass: KClass<T>, jsonValue: JsonValue): Result<T> =
        when (jsonValue) {
            is JsonObject -> decode(kClass, jsonValue)
            is JsonNull ->
                parseFailure(ParseError.InvalidSnapshot("Cannot decode null as ${kClass.qualifiedName}"))
            else ->
                jsonValue.toKotlinPrimitive(kClass)
                    .fold(
                        onSuccess = { raw -> decodeKonstrainedPrimitive(kClass, raw) },
                        onFailure = { error -> Result.failure(error) },
                    )
        }

    private fun decodeValue(kClass: KClass<*>?, json: JsonValue): Result<Any> =
        kClass?.let { decodeValueForClass(it, json) }
            ?: parseFailure(ParseError.InvalidSnapshot("Cannot decode value without type information"))

    private fun decodeValueForClass(kClass: KClass<*>, json: JsonValue): Result<Any> =
        decodeBuiltIn(kClass, json)
            ?: decodeEnum(kClass, json)
            ?: decodeCustomObject(kClass, json)

    private fun decodeBuiltIn(kClass: KClass<*>, json: JsonValue): Result<Any>? =
        when (kClass) {
            Boolean::class ->
                when (json) {
                    is JsonBoolean -> Result.success(json.value)
                    else ->
                        parseFailure(
                            ParseError.InvalidSnapshot("Expected JsonBoolean, got ${json::class.simpleName}"),
                        )
                }

            String::class ->
                when (json) {
                    is JsonString -> Result.success(json.value)
                    else ->
                        parseFailure(
                            ParseError.InvalidSnapshot("Expected JsonString, got ${json::class.simpleName}"),
                        )
                }

            Int::class ->
                when (json) {
                    is JsonNumber -> Result.success(json.toInt())
                    else ->
                        parseFailure(
                            ParseError.InvalidSnapshot("Expected JsonNumber, got ${json::class.simpleName}"),
                        )
                }

            Double::class ->
                when (json) {
                    is JsonNumber -> Result.success(json.toDouble())
                    else ->
                        parseFailure(
                            ParseError.InvalidSnapshot("Expected JsonNumber, got ${json::class.simpleName}"),
                        )
                }

            else -> null
        }

    private fun decodeEnum(kClass: KClass<*>, json: JsonValue): Result<Any>? =
        if (!kClass.java.isEnum) {
            null
        } else {
            when (json) {
                is JsonString -> {
                    @Suppress("UNCHECKED_CAST")
                    val enumClass = kClass.java as Class<out Enum<*>>
                    val enumValue = enumClass.enumConstants.find { it.name == json.value }
                    if (enumValue != null) {
                        Result.success(enumValue)
                    } else {
                        parseFailure(
                            ParseError.InvalidSnapshot(
                                "Unknown enum constant '${json.value}' for ${kClass.simpleName}",
                            ),
                        )
                    }
                }

                else ->
                    parseFailure(
                        ParseError.InvalidSnapshot("Expected JsonString for enum, got ${json::class.simpleName}"),
                    )
            }
        }

    private fun decodeCustomObject(kClass: KClass<*>, json: JsonValue): Result<Any> =
        when (json) {
            is JsonObject -> decode(kClass, json)
            else ->
                parseFailure(
                    ParseError.InvalidSnapshot(
                        "Expected JsonObject for custom type, got ${json::class.simpleName}",
                    ),
                )
        }

    private fun <T : Any> decodeWithoutSchema(kClass: KClass<T>, json: JsonObject): Result<T> {
        // Kotlin `object` singletons have no primary constructor; return the existing instance.
        kClass.objectInstance?.let { return Result.success(it) }
        return kClass.primaryConstructor
            ?.let { constructor ->
                val parametersResult =
                    buildParameterMap(constructor, json, kClass, ::decodeValue)

                if (parametersResult.isSuccess) {
                    runCatching { constructor.callBy(parametersResult.getOrThrow()) }
                        .fold(
                            onSuccess = { Result.success(it) },
                            onFailure = { error ->
                                parseFailure(
                                    ParseError.InvalidSnapshot(
                                        "Failed to instantiate ${kClass.qualifiedName}: ${error.message}",
                                    ),
                                )
                            },
                        )
                } else {
                    Result.failure(
                        parametersResult.exceptionOrNull()
                            ?: IllegalStateException("Unknown constructor parameter decode failure"),
                    )
                }
            }
            ?: parseFailure(
                ParseError.InvalidSnapshot(
                    "${kClass.qualifiedName} must have a primary constructor for deserialization",
                ),
            )
    }

    @Suppress("ReturnCount")
    private fun validateSingletonPayload(
        json: JsonObject,
        schema: ObjectSchema,
        singletonUnknownFieldMode: SingletonUnknownFieldMode,
    ): Result<Unit> {
        val requiredFields = schema.required ?: schema.fields.filter { (_, field) -> field.required }.keys
        val missingRequired =
            requiredFields.filter { requiredField ->
                val fieldSchema = schema.fields[requiredField] ?: return@filter true
                val jsonValue = json.fields[requiredField]
                jsonValue == null && fieldSchema.defaultValue == null
            }
        if (missingRequired.isNotEmpty()) {
            return parseFailure(
                ParseError.InvalidSnapshot(
                    "Required field(s) missing for singleton payload: ${missingRequired.joinToString(", ")}",
                ),
            )
        }

        for ((fieldName, fieldValue) in json.fields) {
            val fieldSchema = schema.fields[fieldName]
            if (fieldSchema == null) {
                if (singletonUnknownFieldMode == SingletonUnknownFieldMode.REJECT_UNKNOWN_FIELDS) {
                    return parseFailure(
                        ParseError.InvalidSnapshot("Unknown field '$fieldName' for singleton payload"),
                    )
                }
                continue
            }

            val validation = fieldValue.validate(fieldSchema.schema)
            if (validation is ValidationResult.Invalid) {
                return parseFailure(
                    ParseError.InvalidSnapshot(
                        "Field '$fieldName' is invalid for singleton payload: ${validation.getErrorMessage()}",
                    ),
                )
            }
        }

        return Result.success(Unit)
    }
}

/**
 * Converts a [JsonValue] to the raw Kotlin value required by
 * [SchemaValueCodec.decodeKonstrainedPrimitive].
 *
 * - [JsonString] → [String]
 * - [JsonBoolean] → [Boolean]
 * - [JsonNumber] → [Int] when [targetClass] is [Konstrained.Primitive.Int] or
 *   [Konstrained.AsInt]; [Double] otherwise
 * - [JsonArray] → [List] of primitives (String, Boolean, Int, or Double per element)
 * - [JsonObject] / [JsonNull] → not handled here; callers must branch before calling
 */
private fun <T : Any> JsonValue.toKotlinPrimitive(targetClass: KClass<T>): Result<Any> =
    when (this) {
        is JsonString -> Result.success(value)
        is JsonBoolean -> Result.success(value)
        is JsonNumber ->
            if (targetClass.isIntKonstrained()) {
                toStrictIntResult().map { strictInt -> strictInt as Any }
            } else {
                Result.success(toDouble())
            }
        is JsonArray ->
            toPrimitiveListResult().map { decodedList -> decodedList as Any }
        else ->
            parseFailure(
                ParseError.InvalidSnapshot(
                    "Cannot convert ${this::class.simpleName} to Kotlin primitive for ${targetClass.qualifiedName}",
                ),
            )
    }

@Suppress("ReturnCount")
private fun JsonNumber.toStrictIntResult(): Result<Int> {
    val raw = toDouble()
    if (raw % 1.0 != 0.0) {
        return parseFailure(ParseError.InvalidSnapshot("Expected integer JSON number, got $raw"))
    }
    if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) {
        return parseFailure(ParseError.InvalidSnapshot("Integer JSON number out of Int range: $raw"))
    }
    return Result.success(raw.toInt())
}

private fun JsonArray.toPrimitiveListResult(): Result<List<Any>> {
    val values = mutableListOf<Any>()
    for ((index, element) in elements.withIndex()) {
        val decodedElement =
            when (element) {
                is JsonString -> Result.success(element.value as Any)
                is JsonBoolean -> Result.success(element.value as Any)
                is JsonNumber -> Result.success(element.toDouble().coerceNumberType())
                else ->
                    parseFailure(
                        ParseError.InvalidSnapshot(
                            "Unsupported array element type at index $index: ${element::class.simpleName}",
                        ),
                    )
            }
        if (decodedElement.isFailure) {
            return Result.failure(
                decodedElement.exceptionOrNull() ?: IllegalStateException("Unknown array decode failure"),
            )
        }
        values += decodedElement.getOrThrow()
    }
    return Result.success(values)
}

private fun Double.coerceNumberType(): Any {
    val intCandidate = toInt()
    return if (this == intCandidate.toDouble()) intCandidate else this
}

private fun KClass<*>.isIntKonstrained(): Boolean =
    isSubclassOf(Konstrained.Primitive.Int::class) || isSubclassOf(Konstrained.AsInt::class)

/**
 * Extracts the single primitive property of the expected type [T] from a [Konstrained] instance.
 *
 * Prefers properties whose declared type exactly matches [T]. Falls back to the sole property
 * if only one exists. Throws with a clear message if zero or multiple candidates are found,
 * directing users toward `@JvmInline value class`.
 */
private inline fun <reified T : Any> Konstrained.extractSinglePrimitiveProperty(): T {
    val kClass = this::class
    val allProps = kClass.memberProperties.toList()

    val primaryConstructorProp =
        kClass.primaryConstructor
            ?.parameters
            ?.singleOrNull()
            ?.name
            ?.let { ctorParamName -> allProps.find { it.name == ctorParamName } }

    val matching = allProps.filter { it.returnType.classifier == T::class }
    val prop =
        when {
            primaryConstructorProp?.returnType?.classifier == T::class -> primaryConstructorProp
            matching.size == 1 -> matching[0]
            matching.isEmpty() && allProps.size == 1 -> allProps[0]
            matching.isEmpty() ->
                error(
                    "${kClass.simpleName} has no property of type ${T::class.simpleName} " +
                        "(found ${allProps.size} properties: ${allProps.map { it.name }}). " +
                        "Consider using @JvmInline value class for compile-time enforcement.",
                )
            else ->
                error(
                    "${kClass.simpleName} has ${matching.size} properties of type ${T::class.simpleName}. " +
                        "Primitive-backed Konstrained requires exactly one. " +
                        "Consider using @JvmInline value class.",
                )
        }
    return prop.call(this) as? T
        ?: error(
            "${kClass.simpleName}.${prop.name} did not return ${T::class.simpleName} " +
                "(got ${prop.call(this)?.let { it::class.simpleName } ?: "null"}).",
        )
}

/** Converts `Any?` to [JsonValue] for array-element encoding. */
private fun Any?.toJsonValue(): JsonValue =
    when (this) {
        null -> JsonNull
        is Boolean -> jsonValue { boolean(this@toJsonValue) }
        is String -> jsonValue { string(this@toJsonValue) }
        is Int -> jsonValue { number(this@toJsonValue) }
        is Double -> jsonValue { number(this@toJsonValue) }
        is JsonValue -> this
        else ->
            error(
                "Unsupported array element type for encoding: ${this::class.qualifiedName}.",
            )
    }

private sealed interface ParameterResolution {
    data class Value(val value: Any?) : ParameterResolution

    data object Skip : ParameterResolution
}

private fun <T : Any> buildParameterMap(
    constructor: kotlin.reflect.KFunction<T>,
    json: JsonObject,
    owner: KClass<*>,
    decodeValue: (KClass<*>?, JsonValue) -> Result<Any>,
): Result<Map<KParameter, Any?>> {
    val result = mutableMapOf<KParameter, Any?>()
    for (param in constructor.parameters) {
        val resolved = resolveParameter(param, json, owner, decodeValue)
        if (resolved.isFailure) {
            return Result.failure(
                resolved.exceptionOrNull()
                    ?: IllegalStateException("Unknown parameter resolution failure"),
            )
        }
        when (val resolution = resolved.getOrThrow()) {
            is ParameterResolution.Value -> result[param] = resolution.value
            ParameterResolution.Skip -> Unit
        }
    }
    return Result.success(result)
}

private fun resolveParameter(
    param: KParameter,
    json: JsonObject,
    owner: KClass<*>,
    decodeValue: (KClass<*>?, JsonValue) -> Result<Any>,
): Result<ParameterResolution> {
    val fieldName = param.name
    val jsonValue = fieldName?.let { json.fields[it] }

    return when {
        fieldName == null ->
            parseFailure(
                ParseError.InvalidSnapshot(
                    "Constructor parameter missing name in ${owner.qualifiedName}",
                ),
            )

        jsonValue == null || jsonValue is JsonNull ->
            if (param.isOptional) {
                Result.success(ParameterResolution.Skip)
            } else {
                parseFailure(
                    ParseError.InvalidSnapshot(
                        "Field '$fieldName' missing in JSON and has no default for ${owner.qualifiedName}",
                    ),
                )
            }

        else ->
            decodeValue(param.type.classifier as? KClass<*>, jsonValue)
                .map { decoded -> ParameterResolution.Value(decoded) }
    }
}

private fun <T : Any> buildSchemaParameterMap(
    constructor: kotlin.reflect.KFunction<T>,
    json: JsonObject,
    schema: ObjectSchema,
    owner: KClass<*>,
    decodeValue: (KClass<*>?, JsonValue) -> Result<Any>,
): Result<Map<KParameter, Any?>> {
    val result = mutableMapOf<KParameter, Any?>()
    for (param in constructor.parameters) {
        val resolved = resolveSchemaParameter(param, json, schema, owner, decodeValue)
        if (resolved.isFailure) {
            return Result.failure(
                resolved.exceptionOrNull()
                    ?: IllegalStateException("Unknown schema parameter resolution failure"),
            )
        }
        when (val resolution = resolved.getOrThrow()) {
            is ParameterResolution.Value -> result[param] = resolution.value
            ParameterResolution.Skip -> Unit
        }
    }
    return Result.success(result)
}

private fun resolveSchemaParameter(
    param: KParameter,
    json: JsonObject,
    schema: ObjectSchema,
    owner: KClass<*>,
    decodeValue: (KClass<*>?, JsonValue) -> Result<Any>,
): Result<ParameterResolution> {
    val fieldName = param.name
    val jsonValue = fieldName?.let { json.fields[it] }
    val fieldSchema = fieldName?.let { schema.fields[it] }

    return when {
        fieldName == null ->
            parseFailure(
                ParseError.InvalidSnapshot(
                    "Constructor parameter missing name in ${owner.qualifiedName}",
                ),
            )

        jsonValue != null && jsonValue !is JsonNull ->
            decodeValue(param.type.classifier as? KClass<*>, jsonValue)
                .map { decoded -> ParameterResolution.Value(decoded) }

        fieldSchema?.defaultValue != null -> Result.success(ParameterResolution.Value(fieldSchema.defaultValue))
        param.isOptional -> Result.success(ParameterResolution.Skip)
        fieldSchema?.required == true ->
            parseFailure(
                ParseError.InvalidSnapshot(
                    "Required field '$fieldName' missing in JSON for ${owner.qualifiedName}",
                ),
            )

        else ->
            parseFailure(
                ParseError.InvalidSnapshot(
                    "Field '$fieldName' missing in JSON and has no default in schema",
                ),
            )
    }
}
