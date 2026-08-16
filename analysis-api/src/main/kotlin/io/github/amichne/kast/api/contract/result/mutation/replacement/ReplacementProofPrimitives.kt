package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ReplacementDeclarationSha256.Serializer::class)
@JvmInline
value class ReplacementDeclarationSha256 private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: [String] -> [ReplacementContractAdmission] of
         * [ReplacementDeclarationSha256].
         *
         * Establishes one lowercase 64-character SHA-256 value. Failure is
         * [ReplacementContractFailure.DECLARATION_SHA256_INVALID]. Raw text may be extracted only
         * at hashing, serialization, or indexer proof-finalization boundaries.
         */
        fun parse(value: String): ReplacementContractAdmission<ReplacementDeclarationSha256> =
            if (value.matches(LOWERCASE_SHA256)) {
                ReplacementContractAdmission.Admitted(ReplacementDeclarationSha256(value))
            } else {
                ReplacementContractAdmission.Rejected(ReplacementContractFailure.DECLARATION_SHA256_INVALID)
            }
    }

    object Serializer : KSerializer<ReplacementDeclarationSha256> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ReplacementDeclarationSha256", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ReplacementDeclarationSha256) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): ReplacementDeclarationSha256 =
            parse(decoder.decodeString()).wireValue()
    }
}

@Serializable(with = ReplacementBodySha256.Serializer::class)
@JvmInline
value class ReplacementBodySha256 private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: [String] -> [ReplacementContractAdmission] of [ReplacementBodySha256].
         *
         * Establishes one lowercase 64-character SHA-256 value. Failure is
         * [ReplacementContractFailure.BODY_SHA256_INVALID]. Raw text may be extracted only at
         * hashing, serialization, or indexer proof-finalization boundaries.
         */
        fun parse(value: String): ReplacementContractAdmission<ReplacementBodySha256> =
            if (value.matches(LOWERCASE_SHA256)) {
                ReplacementContractAdmission.Admitted(ReplacementBodySha256(value))
            } else {
                ReplacementContractAdmission.Rejected(ReplacementContractFailure.BODY_SHA256_INVALID)
            }
    }

    object Serializer : KSerializer<ReplacementBodySha256> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ReplacementBodySha256", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ReplacementBodySha256) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): ReplacementBodySha256 =
            parse(decoder.decodeString()).wireValue()
    }
}

@Serializable
data class ReplacementCompilerContextFile(
    @DocField(description = "Normalized absolute path of an unchanged Kotlin or Java compiler-context file.")
    val filePath: ExactFileImagePath,
    @DocField(description = "SHA-256 of the exact unchanged compiler-context file bytes.")
    val sha256: ExactFileImageSha256,
)

@Serializable(with = ReplacementCompilerModelGeneration.Serializer::class)
@JvmInline
value class ReplacementCompilerModelGeneration private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition: [Long] -> [ReplacementContractAdmission] of
         * [ReplacementCompilerModelGeneration].
         *
         * Establishes a non-negative IntelliJ project-model generation. Failure is
         * [ReplacementContractFailure.COMPILER_MODEL_GENERATION_NEGATIVE]. Raw values may be
         * extracted only at serialization or IntelliJ project-model observation boundaries.
         */
        fun parse(value: Long): ReplacementContractAdmission<ReplacementCompilerModelGeneration> =
            if (value >= 0L) {
                ReplacementContractAdmission.Admitted(ReplacementCompilerModelGeneration(value))
            } else {
                ReplacementContractAdmission.Rejected(
                    ReplacementContractFailure.COMPILER_MODEL_GENERATION_NEGATIVE,
                )
            }
    }

    object Serializer : KSerializer<ReplacementCompilerModelGeneration> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ReplacementCompilerModelGeneration", PrimitiveKind.LONG)

        override fun serialize(encoder: Encoder, value: ReplacementCompilerModelGeneration) {
            encoder.encodeLong(value.value)
        }

        override fun deserialize(decoder: Decoder): ReplacementCompilerModelGeneration =
            parse(decoder.decodeLong()).wireValue()
    }
}

/**
 * Canonical compiler-visible source context admitted for an exact replacement proof.
 */
@Serializable(with = ReplacementCompilerContext.Serializer::class)
class ReplacementCompilerContext private constructor(
    private val storedFiles: List<ReplacementCompilerContextFile>,
    val modelGeneration: ReplacementCompilerModelGeneration,
) {
    val files: List<ReplacementCompilerContextFile>
        get() = Collections.unmodifiableList(storedFiles)

    override fun equals(other: Any?): Boolean =
        other is ReplacementCompilerContext &&
            storedFiles == other.storedFiles &&
            modelGeneration == other.modelGeneration

    override fun hashCode(): Int = 31 * storedFiles.hashCode() + modelGeneration.hashCode()

    override fun toString(): String =
        "ReplacementCompilerContext(files=${storedFiles.size}, modelGeneration=${modelGeneration.value})"

    companion object {
        /**
         * Proof transition: typed path/hash map plus [ReplacementCompilerModelGeneration] ->
         * [ReplacementCompilerContext].
         *
         * Establishes canonical path-sorted, unique compiler-context evidence. Raw paths and
         * hashes may be extracted only at the serialization or indexer workspace-observation
         * boundary.
         */
        fun of(
            filesByPath: Map<ExactFileImagePath, ExactFileImageSha256>,
            modelGeneration: ReplacementCompilerModelGeneration,
        ): ReplacementCompilerContext = ReplacementCompilerContext(
            storedFiles = filesByPath.entries
                .map { (filePath, sha256) -> ReplacementCompilerContextFile(filePath, sha256) }
                .sortedBy { file -> file.filePath.value },
            modelGeneration = modelGeneration,
        )

        /**
         * Proof transition: wire [List] plus [ReplacementCompilerModelGeneration] ->
         * [ReplacementContractAdmission] of [ReplacementCompilerContext].
         *
         * Establishes deterministic order and unique paths. Failures are
         * [ReplacementContractFailure.COMPILER_CONTEXT_NOT_SORTED] and
         * [ReplacementContractFailure.COMPILER_CONTEXT_DUPLICATE_PATH]. Raw lists may be
         * extracted only by [Serializer].
         */
        fun admit(
            files: List<ReplacementCompilerContextFile>,
            modelGeneration: ReplacementCompilerModelGeneration,
        ): ReplacementContractAdmission<ReplacementCompilerContext> = when {
            files != files.sortedBy { file -> file.filePath.value } ->
                ReplacementContractAdmission.Rejected(
                    ReplacementContractFailure.COMPILER_CONTEXT_NOT_SORTED,
                )

            files.map { file -> file.filePath }.distinct().size != files.size ->
                ReplacementContractAdmission.Rejected(
                    ReplacementContractFailure.COMPILER_CONTEXT_DUPLICATE_PATH,
                )

            else -> ReplacementContractAdmission.Admitted(
                ReplacementCompilerContext(files.toList(), modelGeneration),
            )
        }
    }

    object Serializer : KSerializer<ReplacementCompilerContext> {
        override val descriptor: SerialDescriptor = ReplacementCompilerContextWire.serializer().descriptor

        override fun serialize(encoder: Encoder, value: ReplacementCompilerContext) {
            encoder.encodeSerializableValue(
                ReplacementCompilerContextWire.serializer(),
                ReplacementCompilerContextWire(value.files, value.modelGeneration),
            )
        }

        override fun deserialize(decoder: Decoder): ReplacementCompilerContext {
            val wire = decoder.decodeSerializableValue(ReplacementCompilerContextWire.serializer())
            return admit(wire.files, wire.modelGeneration).wireValue()
        }
    }
}

@Serializable
@SerialName("ReplacementCompilerContext")
private data class ReplacementCompilerContextWire(
    val files: List<ReplacementCompilerContextFile>,
    val modelGeneration: ReplacementCompilerModelGeneration,
)

@Serializable(with = ReplacementDeclarationSlice.Serializer::class)
data class ReplacementDeclarationSlice private constructor(
    @DocField(description = "UTF-16 start offset of the Kotlin function declaration inside the submitted declaration text.")
    val startOffset: NonNegativeInt,
    @DocField(description = "UTF-16 end offset of the Kotlin function declaration inside the submitted declaration text.")
    val endOffset: NonNegativeInt,
) {
    companion object {
        /**
         * Proof transition: two [NonNegativeInt] offsets -> [ReplacementContractAdmission] of
         * [ReplacementDeclarationSlice].
         *
         * Establishes a non-empty submitted declaration range. Failure is
         * [ReplacementContractFailure.DECLARATION_SLICE_EMPTY]. Raw offsets may be extracted only
         * at copied-PSI or serialization boundaries.
         */
        fun of(
            startOffset: NonNegativeInt,
            endOffset: NonNegativeInt,
        ): ReplacementContractAdmission<ReplacementDeclarationSlice> =
            if (endOffset.value > startOffset.value) {
                ReplacementContractAdmission.Admitted(
                    ReplacementDeclarationSlice(startOffset, endOffset),
                )
            } else {
                ReplacementContractAdmission.Rejected(ReplacementContractFailure.DECLARATION_SLICE_EMPTY)
            }
    }

    object Serializer : KSerializer<ReplacementDeclarationSlice> {
        override val descriptor: SerialDescriptor = ReplacementDeclarationSliceWire.serializer().descriptor

        override fun serialize(encoder: Encoder, value: ReplacementDeclarationSlice) {
            encoder.encodeSerializableValue(
                ReplacementDeclarationSliceWire.serializer(),
                ReplacementDeclarationSliceWire(value.startOffset, value.endOffset),
            )
        }

        override fun deserialize(decoder: Decoder): ReplacementDeclarationSlice {
            val wire = decoder.decodeSerializableValue(ReplacementDeclarationSliceWire.serializer())
            return of(wire.startOffset, wire.endOffset).wireValue()
        }
    }
}

@Serializable(with = ReplacementSubmittedBodySlice.Serializer::class)
data class ReplacementSubmittedBodySlice private constructor(
    @DocField(description = "UTF-16 start offset of the extracted function body inside the submitted declaration text.")
    val startOffset: NonNegativeInt,
    @DocField(description = "UTF-16 end offset of the extracted function body inside the submitted declaration text.")
    val endOffset: NonNegativeInt,
) {
    companion object {
        /**
         * Proof transition: two [NonNegativeInt] offsets -> [ReplacementContractAdmission] of
         * [ReplacementSubmittedBodySlice].
         *
         * Establishes a non-empty body range inside submitted text. Failure is
         * [ReplacementContractFailure.BODY_SLICE_EMPTY]. Raw offsets may be extracted only at
         * copied-PSI or serialization boundaries.
         */
        fun of(
            startOffset: NonNegativeInt,
            endOffset: NonNegativeInt,
        ): ReplacementContractAdmission<ReplacementSubmittedBodySlice> =
            if (endOffset.value > startOffset.value) {
                ReplacementContractAdmission.Admitted(
                    ReplacementSubmittedBodySlice(startOffset, endOffset),
                )
            } else {
                ReplacementContractAdmission.Rejected(ReplacementContractFailure.BODY_SLICE_EMPTY)
            }
    }

    object Serializer : KSerializer<ReplacementSubmittedBodySlice> {
        override val descriptor: SerialDescriptor = ReplacementSubmittedBodySliceWire.serializer().descriptor

        override fun serialize(encoder: Encoder, value: ReplacementSubmittedBodySlice) {
            encoder.encodeSerializableValue(
                ReplacementSubmittedBodySliceWire.serializer(),
                ReplacementSubmittedBodySliceWire(value.startOffset, value.endOffset),
            )
        }

        override fun deserialize(decoder: Decoder): ReplacementSubmittedBodySlice {
            val wire = decoder.decodeSerializableValue(ReplacementSubmittedBodySliceWire.serializer())
            return of(wire.startOffset, wire.endOffset).wireValue()
        }
    }
}

@Serializable
@SerialName("ReplacementDeclarationSlice")
private data class ReplacementDeclarationSliceWire(
    @SerialName("startOffset")
    val startOffset: NonNegativeInt,
    @SerialName("endOffset")
    val endOffset: NonNegativeInt,
)

@Serializable
@SerialName("ReplacementSubmittedBodySlice")
private data class ReplacementSubmittedBodySliceWire(
    @SerialName("startOffset")
    val startOffset: NonNegativeInt,
    @SerialName("endOffset")
    val endOffset: NonNegativeInt,
)

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
