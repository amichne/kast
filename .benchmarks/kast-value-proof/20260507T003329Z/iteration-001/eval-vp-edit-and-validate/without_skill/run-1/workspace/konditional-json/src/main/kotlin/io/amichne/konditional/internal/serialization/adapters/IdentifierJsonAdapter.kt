package io.amichne.konditional.internal.serialization.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.values.FeatureId
import java.lang.reflect.Type

/**
 * Moshi adapter for [FeatureId].
 *
 * This adapter is intentionally explicit (vs. relying on reflection/value-class adapters) because:
 * - [FeatureId] has a private constructor with strict invariants.
 * - The on-wire format historically used `value::...` and is still accepted for backwards compatibility.
 */
@KonditionalInternalApi
internal object IdentifierJsonAdapter : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? =
        FeatureIdAdapter.takeIf {
            annotations.isEmpty() && type == FeatureId::class.java
        }

    private object FeatureIdAdapter : JsonAdapter<FeatureId>() {
        override fun toJson(
            writer: JsonWriter,
            value: FeatureId?,
        ) {
            if (value == null) {
                writer.nullValue()
                return
            }
            writer.value(value.plainId)
        }

        override fun fromJson(reader: JsonReader): FeatureId {
            val plainId = reader.nextString()
            return try {
                FeatureId.parse(plainId)
            } catch (e: IllegalArgumentException) {
                throw JsonDataException("Invalid FeatureId '$plainId' at path ${reader.path}", e)
            }
        }
    }
}
