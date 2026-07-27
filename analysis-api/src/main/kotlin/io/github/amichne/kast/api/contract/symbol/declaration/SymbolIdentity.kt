package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class SymbolIdentity(
    @DocField(description = "Compiler-resolved fully-qualified declaration name.")
    val fqName: String,
    @DocField(description = "Compiler-resolved declaration kind.")
    val kind: SymbolKind,
    @DocField(description = "Canonical absolute path to the declaration source file.")
    @Serializable(with = SymbolIdentityNormalizedPathSerializer::class)
    val declarationFile: NormalizedPath,
    @DocField(description = "Zero-based declaration start offset in the canonical source file.")
    @Serializable(with = SymbolIdentityNonNegativeIntSerializer::class)
    val declarationStartOffset: NonNegativeInt,
    @DocField(description = "Fully-qualified containing type when the declaration is a member.")
    val containingType: String? = null,
) {
    init {
        require(fqName.isNotBlank()) { "Symbol identity FQ name must not be blank" }
        require(containingType == null || containingType.isNotBlank()) {
            "Symbol identity containing type must be null or non-blank"
        }
    }
}

private object SymbolIdentityNormalizedPathSerializer : KSerializer<NormalizedPath> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NormalizedPath", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NormalizedPath) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): NormalizedPath =
        NormalizedPath.parse(decoder.decodeString())
}

private object SymbolIdentityNonNegativeIntSerializer : KSerializer<NonNegativeInt> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NonNegativeInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: NonNegativeInt) {
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): NonNegativeInt =
        NonNegativeInt(decoder.decodeInt())
}
