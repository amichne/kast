package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Separate exact entry formats retain the original V1 shape when a legacy view was actually read. */
@Serializable(with = MainBuildEntrySerializer::class)
internal sealed interface MainBuildEntry {
    val mainRevision: String
    val buildRevision: String
    val sourceTree: String
    val releaseTag: String
    val version: String
    val platformTargets: List<String>
    val candidate: BuildRecordReference
    val promotion: BuildRecordReference
}

@Serializable
internal data class LegacyMainBuildEntry(
    override val mainRevision: String,
    override val buildRevision: String,
    override val sourceTree: String,
    override val releaseTag: String,
    override val version: String,
    override val platformTargets: List<String>,
    override val candidate: BuildRecordReference,
    override val promotion: BuildRecordReference,
) : MainBuildEntry

@Serializable
internal data class VersionedMainBuildEntry(
    override val mainRevision: String,
    override val buildRevision: String,
    override val sourceTree: String,
    override val releaseTag: String,
    override val version: String,
    override val platformTargets: List<String>,
    override val candidate: BuildRecordReference,
    override val promotion: BuildRecordReference,
    val hostTargets: List<MainHostTarget>,
) : MainBuildEntry

@Serializable
internal data class MainHostTarget(
    val intellijReleaseLine: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val record: BuildRecordReference,
)

/** Captured selections add no discriminator or optional fields inside their historically exact catalogEntry. */
internal object MainBuildEntrySerializer : KSerializer<MainBuildEntry> {
    override val descriptor = buildClassSerialDescriptor("MainBuildEntry")

    override fun serialize(encoder: Encoder, value: MainBuildEntry) {
        when (value) {
            is LegacyMainBuildEntry -> encoder.encodeSerializableValue(LegacyMainBuildEntry.serializer(), value)
            is VersionedMainBuildEntry -> encoder.encodeSerializableValue(VersionedMainBuildEntry.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): MainBuildEntry {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Catalog entry requires JSON")
        val element = input.decodeJsonElement()
        val fields = element as? JsonObject ?: throw SerializationException("Catalog entry requires an object")
        return if ("hostTargets" in fields) input.json.decodeFromJsonElement<VersionedMainBuildEntry>(element)
        else input.json.decodeFromJsonElement<LegacyMainBuildEntry>(element)
    }
}
