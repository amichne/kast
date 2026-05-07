@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.serialization.snapshot

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.parseFailure
import io.amichne.konditional.core.schema.CompiledNamespaceSchema
import io.amichne.konditional.internal.serialization.adapters.FlagValueAdapterFactory
import io.amichne.konditional.internal.serialization.adapters.IdentifierJsonAdapter
import io.amichne.konditional.internal.serialization.adapters.ValueClassAdapterFactory
import io.amichne.konditional.internal.serialization.adapters.VersionRangeAdapter
import io.amichne.konditional.internal.serialization.models.SerializableSnapshot
import io.amichne.konditional.rules.versions.FullyBound
import io.amichne.konditional.rules.versions.LeftBound
import io.amichne.konditional.rules.versions.RightBound
import io.amichne.konditional.rules.versions.Unbounded
import io.amichne.konditional.rules.versions.VersionRange

/**
 * Strict JSON codec for exported namespace snapshots.
 */
internal object ConfigurationCodec {
    private val moshi = defaultMoshi()
    private val snapshotAdapter = moshi.adapter(SerializableSnapshot::class.java).indent("  ")

    fun encode(configuration: Configuration): String =
        snapshotAdapter.toJson(SerializableSnapshot.from(configuration))

    fun encode(namespace: Namespace): String =
        encode(namespace.configuration)

    fun decode(
        json: String,
        namespace: Namespace,
    ): Result<Configuration> =
        runCatching { snapshotAdapter.fromJson(json) }
            .fold(
                onSuccess = { snapshot ->
                    snapshot?.toConfiguration(CompiledNamespaceSchema.from(namespace))
                        ?: parseFailure(ParseError.invalidJson("Failed to parse JSON: null snapshot"))
                },
                onFailure = { error ->
                    parseFailure(
                        ParseError.invalidJson(error.message ?: "Unknown JSON parsing error"),
                    )
                },
            )

    internal fun defaultMoshi(): Moshi =
        Moshi.Builder()
            .add(IdentifierJsonAdapter)
            .add(ValueClassAdapterFactory)
            .add(FlagValueAdapterFactory)
            .add(
                VersionRangeAdapter(
                    Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
                ),
            ).add(
                PolymorphicJsonAdapterFactory
                    .of(VersionRange::class.java, "type")
                    .withSubtype(FullyBound::class.java, VersionRange.Type.MIN_AND_MAX_BOUND.name)
                    .withSubtype(Unbounded::class.java, VersionRange.Type.UNBOUNDED.name)
                    .withSubtype(LeftBound::class.java, VersionRange.Type.MIN_BOUND.name)
                    .withSubtype(RightBound::class.java, VersionRange.Type.MAX_BOUND.name),
            ).addLast(KotlinJsonAdapterFactory())
            .build()
}
