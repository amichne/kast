package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.api.validation.ExactTextEditReplayException
import io.github.amichne.kast.api.validation.ExactTextEditReplayValidator
import io.github.amichne.kast.api.validation.FileHashing
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ReplacementPlanResult.Serializer::class)
class ReplacementPlanResult private constructor(
    @DocField(description = "Single non-mutating edit that replaces the exact selected Kotlin function body.")
    val edit: TextEdit,
    @DocField(description = "Required compiler-backed proof for the replacement plan.")
    val proof: ExactReplacementProof,
    @DocField(description = "Exact immutable preimage and postimage bytes for the replacement file.")
    private val storedFileImages: List<ExactFileImage>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val fileImages: List<ExactFileImage>
        get() = Collections.unmodifiableList(storedFileImages)

    override fun equals(other: Any?): Boolean =
        other is ReplacementPlanResult &&
        edit == other.edit &&
        proof == other.proof &&
        storedFileImages == other.storedFileImages &&
        schemaVersion == other.schemaVersion

    override fun hashCode(): Int = listOf(edit, proof, storedFileImages, schemaVersion).hashCode()

    override fun toString(): String =
        "ReplacementPlanResult(edit=$edit, proof=$proof, fileImages=$storedFileImages, schemaVersion=$schemaVersion)"

    companion object {
        /**
         * Proof transition: [TextEdit], [ExactReplacementProof], and exact file images ->
         * [ReplacementContractAdmission] of [ReplacementPlanResult].
         *
         * Establishes that the only edit is the proven body write, its body text matches the
         * admitted proof, its one preimage is hash-bound, and deterministic replay produces the
         * distinct claimed postimage. Failure is the closed [ReplacementContractFailure] family.
         * Raw edit text and image bytes may be extracted only at mutation planning, serialization,
         * or exact-file CAS boundaries.
         */
        fun admit(
            edit: TextEdit,
            proof: ExactReplacementProof,
            fileImages: List<ExactFileImage>,
            schemaVersion: Int = SCHEMA_VERSION,
        ): ReplacementContractAdmission<ReplacementPlanResult> {
            val structuralFailure = when {
                edit.filePath != proof.sourceRange.filePath ||
                edit.startOffset != proof.sourceRange.startOffset ||
                edit.endOffset != proof.sourceRange.endOffset ->
                    ReplacementContractFailure.EDIT_RANGE_MISMATCH

                edit.newText.length != proof.proposedBodyLength ->
                    ReplacementContractFailure.EDIT_BODY_LENGTH_MISMATCH

                FileHashing.sha256(edit.newText) != proof.proposedBodyHash.value ->
                    ReplacementContractFailure.EDIT_BODY_HASH_MISMATCH

                proof.outboundReferences.any { reference ->
                    edit.newText.substring(reference.relativeStartOffset, reference.relativeEndOffset) !=
                        reference.sourceText
                } -> ReplacementContractFailure.EDIT_OUTBOUND_TEXT_MISMATCH

                fileImages.size != 1 || fileImages.single().filePath.value != edit.filePath ->
                    ReplacementContractFailure.FILE_IMAGE_SET_MISMATCH

                proof.fileHashes.single().filePath != edit.filePath ||
                proof.fileHashes.single().hash != fileImages.single().preimage.sha256.value ->
                    ReplacementContractFailure.FILE_HASH_PREIMAGE_MISMATCH

                fileImages.single().preimage.sha256 == fileImages.single().postimage.sha256 ->
                    ReplacementContractFailure.POSTIMAGE_UNCHANGED

                else -> null
            }
            if (structuralFailure != null) {
                return ReplacementContractAdmission.Rejected(structuralFailure)
            }
            try {
                ExactTextEditReplayValidator.requireExactPostimages(listOf(edit), fileImages)
            } catch (_: ExactTextEditReplayException) {
                return ReplacementContractAdmission.Rejected(
                    ReplacementContractFailure.POSTIMAGE_REPLAY_INVALID,
                )
            }
            return ReplacementContractAdmission.Admitted(
                ReplacementPlanResult(
                    edit = edit,
                    proof = proof,
                    storedFileImages = fileImages.toList(),
                    schemaVersion = schemaVersion,
                ),
            )
        }
    }

    object Serializer : KSerializer<ReplacementPlanResult> {
        override val descriptor: SerialDescriptor = ReplacementPlanResultWire.serializer().descriptor

        override fun serialize(
            encoder: Encoder,
            value: ReplacementPlanResult
        ) {
            encoder.encodeSerializableValue(
                ReplacementPlanResultWire.serializer(),
                ReplacementPlanResultWire(
                    edit = value.edit,
                    proof = value.proof,
                    fileImages = value.fileImages,
                    schemaVersion = value.schemaVersion,
                ),
            )
        }

        override fun deserialize(decoder: Decoder): ReplacementPlanResult {
            val wire = decoder.decodeSerializableValue(ReplacementPlanResultWire.serializer())
            return admit(
                edit = wire.edit,
                proof = wire.proof,
                fileImages = wire.fileImages,
                schemaVersion = wire.schemaVersion,
            ).wireValue()
        }
    }
}

@Serializable
@SerialName("ReplacementPlanResult")
private data class ReplacementPlanResultWire(
    val edit: TextEdit,
    val proof: ExactReplacementProof,
    @SerialName("fileImages")
    val fileImages: List<ExactFileImage>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
