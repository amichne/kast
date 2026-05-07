package io.amichne.konditional.internal.serialization.models

import com.squareup.moshi.JsonClass
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.instance.ConfigurationMetadata
import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.parseFailure
import io.amichne.konditional.core.schema.CompiledNamespaceSchema

/**
 * Strict serializable representation of a namespace snapshot.
 */
@KonditionalInternalApi
@JsonClass(generateAdapter = true)
internal data class SerializableSnapshot(
    val meta: SerializableSnapshotMetadata? = null,
    val flags: List<SerializableFlag>,
) {
    fun toConfiguration(
        schema: CompiledNamespaceSchema,
    ): Result<Configuration> =
        materializedFlags(schema).map { resolvedFlags ->
            Configuration(
                flags = resolvedFlags,
                metadata = meta?.toConfigurationMetadata() ?: ConfigurationMetadata(),
            )
        }

    private fun materializedFlags(
        schema: CompiledNamespaceSchema,
    ): Result<LinkedHashMap<Feature<*, *, *>, FlagDefinition<*, *, *>>> {
        val resolvedFlags = linkedMapOf<Feature<*, *, *>, FlagDefinition<*, *, *>>()
        for (serializableFlag in flags) {
            val pair = serializableFlag.toFlagPair(schema).getOrElse { return parseFailure(it.parseError()) }
            resolvedFlags[pair.first] = pair.second
        }

        val missingDeclared = schema.entriesInDeterministicOrder.filter { entry ->
            resolvedFlags[entry.feature] == null
        }
        if (missingDeclared.isNotEmpty()) {
            val missingIds = missingDeclared.joinToString(", ") { entry -> entry.featureId.toString() }
            return parseFailure(
                ParseError.invalidSnapshot(
                    "Missing declared flags for namespace '${schema.namespaceId}': $missingIds",
                ),
            )
        }

        return Result.success(resolvedFlags)
    }

    companion object {
        fun from(configuration: Configuration): SerializableSnapshot =
            SerializableSnapshot(
                meta = SerializableSnapshotMetadata.from(configuration.metadata),
                flags = configuration.flags.map { (feature, flag) ->
                    SerializableFlag.from(flag, feature.id)
                },
            )

        private fun Throwable.parseError(): ParseError =
            (this as? io.amichne.konditional.core.result.KonditionalBoundaryFailure)?.parseError
                ?: ParseError.invalidSnapshot(message ?: "Unknown materialization failure")
    }
}
