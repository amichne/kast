@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.serialization.internal

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.types.Konstrained
import io.amichne.konditional.serialization.SchemaValueCodec
import io.amichne.konditional.serialization.extractSchema

/**
 * Encodes an object-backed [Konstrained] instance to a `Map<String, Any?>` of its field values.
 *
 * This function is only valid for [Konstrained.Object] implementations. Primitive, array, and adapted
 * [Konstrained] variants should use [SchemaValueCodec.encodeKonstrained] instead.
 *
 * @throws IllegalArgumentException if the value is not object-backed or its reflective schema
 *   cannot be extracted.
 */
@KonditionalInternalApi
internal fun Konstrained.toPrimitiveMap(): Map<String, Any?> {
    require(this is Konstrained.Object) {
        "toPrimitiveMap() is only supported for object-backed Konstrained values. " +
            "For primitive, array, or adapted shapes use SchemaValueCodec.encodeKonstrained()."
    }
    val schema =
        extractSchema(this::class)
            ?: error("Cannot extract ObjectSchema from ${this::class.qualifiedName}")
    return SchemaValueCodec.encode(this, schema)
        .toPrimitiveValue()
        .let { primitive ->
            require(primitive is Map<*, *>) {
                "Object-backed Konstrained must encode to a map, got ${primitive?.let { it::class.simpleName }}"
            }
            @Suppress("UNCHECKED_CAST")
            primitive as Map<String, Any?>
        }
}
