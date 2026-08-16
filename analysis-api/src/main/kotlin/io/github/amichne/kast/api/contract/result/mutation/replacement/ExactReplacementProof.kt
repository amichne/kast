package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ExactReplacementProof.Serializer::class)
class ExactReplacementProof private constructor(
    @DocField(description = "Exact compiler-resolved identity of the source declaration.")
    val target: SymbolIdentity,
    @DocField(description = "Semantic source generation required by this replacement proof.")
    val requiredGeneration: MutationSemanticGeneration,
    @DocField(description = "Exact source range of the selected Kotlin function body to replace.")
    val sourceRange: Location,
    @DocField(description = "Exact source file hashes that bind this replacement proof.")
    private val storedFileHashes: List<FileHash>,
    @DocField(description = "Exact unchanged Kotlin and Java compiler context outside the replacement write set.")
    val compilerContext: ReplacementCompilerContext,
    @DocField(description = "Typed compiler-observable signature of the existing declaration.")
    val oldSignature: ReplacementDeclarationSignature,
    @DocField(description = "Typed compiler-observable signature of the proposed declaration.")
    val proposedSignature: ReplacementDeclarationSignature,
    @DocField(description = "SHA-256 of the exact submitted Kotlin function declaration.")
    val proposedDeclarationHash: ReplacementDeclarationSha256,
    @DocField(description = "Exact UTF-16 length of the submitted Kotlin function declaration.")
    val proposedDeclarationLength: Int,
    @DocField(description = "SHA-256 of the exact extracted Kotlin function body that authorizes the write.")
    val proposedBodyHash: ReplacementBodySha256,
    @DocField(description = "Exact UTF-16 length of the extracted Kotlin function body that authorizes the write.")
    val proposedBodyLength: Int,
    @DocField(description = "Exact Kotlin function declaration range inside the submitted declaration text.")
    val declarationSlice: ReplacementDeclarationSlice,
    @DocField(description = "Exact extracted function-body range inside the submitted declaration text.")
    val proposedBodySlice: ReplacementSubmittedBodySlice,
    @DocField(description = "Operation-relative complete outbound-reference proof and exact occurrence cardinality.")
    @Serializable(with = ReplacementOutboundEvidence.CompleteSerializer::class)
    val evidence: ReplacementOutboundEvidence.Complete,
    @DocField(description = "Every compiler-resolved outbound reference in the proposed Kotlin function body.")
    private val storedOutboundReferences: List<ExactReplacementOutboundReference>,
) {
    val fileHashes: List<FileHash>
        get() = Collections.unmodifiableList(storedFileHashes)
    val outboundReferences: List<ExactReplacementOutboundReference>
        get() = Collections.unmodifiableList(storedOutboundReferences)

    override fun equals(other: Any?): Boolean =
        other is ExactReplacementProof &&
            target == other.target &&
            requiredGeneration == other.requiredGeneration &&
            sourceRange == other.sourceRange &&
            storedFileHashes == other.storedFileHashes &&
            compilerContext == other.compilerContext &&
            oldSignature == other.oldSignature &&
            proposedSignature == other.proposedSignature &&
            proposedDeclarationHash == other.proposedDeclarationHash &&
            proposedDeclarationLength == other.proposedDeclarationLength &&
            proposedBodyHash == other.proposedBodyHash &&
            proposedBodyLength == other.proposedBodyLength &&
            declarationSlice == other.declarationSlice &&
            proposedBodySlice == other.proposedBodySlice &&
            evidence == other.evidence &&
            storedOutboundReferences == other.storedOutboundReferences

    override fun hashCode(): Int = listOf(
        target,
        requiredGeneration,
        sourceRange,
        storedFileHashes,
        compilerContext,
        oldSignature,
        proposedSignature,
        proposedDeclarationHash,
        proposedDeclarationLength,
        proposedBodyHash,
        proposedBodyLength,
        declarationSlice,
        proposedBodySlice,
        evidence,
        storedOutboundReferences,
    ).hashCode()

    companion object {
        /**
         * Proof transition: exact replacement contract fields -> [ReplacementContractAdmission] of
         * [ExactReplacementProof].
         *
         * Establishes function-only identity, exact target-body authority, equal typed signatures,
         * distinct request/body evidence, one target preimage, unchanged compiler context, and
         * exact compiler-proven outbound cardinality/ranges. Failure is the closed
         * [ReplacementContractFailure] family. Raw collections and primitive lengths may be
         * extracted only at indexer proof finalization or [Serializer].
         */
        @Suppress("LongParameterList")
        fun admit(
            target: SymbolIdentity,
            requiredGeneration: MutationSemanticGeneration,
            sourceRange: Location,
            fileHashes: List<FileHash>,
            compilerContext: ReplacementCompilerContext,
            oldSignature: ReplacementDeclarationSignature,
            proposedSignature: ReplacementDeclarationSignature,
            proposedDeclarationHash: ReplacementDeclarationSha256,
            proposedDeclarationLength: Int,
            proposedBodyHash: ReplacementBodySha256,
            proposedBodyLength: Int,
            declarationSlice: ReplacementDeclarationSlice,
            proposedBodySlice: ReplacementSubmittedBodySlice,
            evidence: ReplacementOutboundEvidence.Complete,
            outboundReferences: List<ExactReplacementOutboundReference>,
        ): ReplacementContractAdmission<ExactReplacementProof> {
            val failure = when {
                target.kind != SymbolKind.FUNCTION ->
                    ReplacementContractFailure.TARGET_NOT_FUNCTION

                sourceRange.filePath != target.declarationFile.value ->
                    ReplacementContractFailure.SOURCE_RANGE_TARGET_MISMATCH

                target.declarationStartOffset.value >= sourceRange.startOffset ->
                    ReplacementContractFailure.SOURCE_RANGE_BEFORE_DECLARATION

                fileHashes.size != 1 ||
                    fileHashes.single().filePath != sourceRange.filePath ||
                    !fileHashes.single().hash.matches(LOWERCASE_SHA256) ->
                    ReplacementContractFailure.SOURCE_FILE_HASH_INVALID

                compilerContext.files.any { file -> file.filePath.value == sourceRange.filePath } ->
                    ReplacementContractFailure.COMPILER_CONTEXT_CONTAINS_TARGET

                oldSignature != proposedSignature ->
                    ReplacementContractFailure.SIGNATURE_DRIFT

                oldSignature !is ReplacementFunctionSignature ||
                    proposedSignature !is ReplacementFunctionSignature ->
                    ReplacementContractFailure.SIGNATURE_NOT_FUNCTION

                proposedDeclarationLength <= 0 ->
                    ReplacementContractFailure.DECLARATION_LENGTH_INVALID

                proposedBodyLength <= 0 ->
                    ReplacementContractFailure.BODY_LENGTH_INVALID

                declarationSlice.endOffset.value > proposedDeclarationLength ->
                    ReplacementContractFailure.DECLARATION_SLICE_OUT_OF_BOUNDS

                proposedBodySlice.startOffset.value < declarationSlice.startOffset.value ||
                    proposedBodySlice.endOffset.value > declarationSlice.endOffset.value ||
                    proposedBodySlice.endOffset.value - proposedBodySlice.startOffset.value != proposedBodyLength ->
                    ReplacementContractFailure.BODY_SLICE_OUT_OF_BOUNDS

                evidence.cardinality.totalCount != outboundReferences.size ->
                    ReplacementContractFailure.OUTBOUND_CARDINALITY_MISMATCH

                outboundReferences.any { reference ->
                    reference.provenance != ReplacementOccurrenceProvenance.COMPILER ||
                        reference.relativeStartOffset < 0 ||
                        reference.relativeEndOffset <= reference.relativeStartOffset ||
                        reference.relativeEndOffset > proposedBodyLength
                } -> ReplacementContractFailure.OUTBOUND_REFERENCE_RANGE_INVALID

                outboundReferences.map { reference ->
                    reference.relativeStartOffset to reference.relativeEndOffset
                }.distinct().size != outboundReferences.size ->
                    ReplacementContractFailure.OUTBOUND_REFERENCE_RANGE_DUPLICATE

                else -> null
            }
            return if (failure == null) {
                ReplacementContractAdmission.Admitted(
                    ExactReplacementProof(
                        target = target,
                        requiredGeneration = requiredGeneration,
                        sourceRange = sourceRange,
                        storedFileHashes = fileHashes.toList(),
                        compilerContext = compilerContext,
                        oldSignature = oldSignature,
                        proposedSignature = proposedSignature,
                        proposedDeclarationHash = proposedDeclarationHash,
                        proposedDeclarationLength = proposedDeclarationLength,
                        proposedBodyHash = proposedBodyHash,
                        proposedBodyLength = proposedBodyLength,
                        declarationSlice = declarationSlice,
                        proposedBodySlice = proposedBodySlice,
                        evidence = evidence,
                        storedOutboundReferences = outboundReferences.toList(),
                    ),
                )
            } else {
                ReplacementContractAdmission.Rejected(failure)
            }
        }
    }

    object Serializer : KSerializer<ExactReplacementProof> {
        override val descriptor: SerialDescriptor = ExactReplacementProofWire.serializer().descriptor

        override fun serialize(encoder: Encoder, value: ExactReplacementProof) {
            encoder.encodeSerializableValue(
                ExactReplacementProofWire.serializer(),
                ExactReplacementProofWire(
                    target = value.target,
                    requiredGeneration = value.requiredGeneration,
                    sourceRange = value.sourceRange,
                    fileHashes = value.fileHashes,
                    compilerContext = value.compilerContext,
                    oldSignature = value.oldSignature,
                    proposedSignature = value.proposedSignature,
                    proposedDeclarationHash = value.proposedDeclarationHash,
                    proposedDeclarationLength = value.proposedDeclarationLength,
                    proposedBodyHash = value.proposedBodyHash,
                    proposedBodyLength = value.proposedBodyLength,
                    declarationSlice = value.declarationSlice,
                    proposedBodySlice = value.proposedBodySlice,
                    evidence = value.evidence,
                    outboundReferences = value.outboundReferences,
                ),
            )
        }

        override fun deserialize(decoder: Decoder): ExactReplacementProof {
            val wire = decoder.decodeSerializableValue(ExactReplacementProofWire.serializer())
            return admit(
                target = wire.target,
                requiredGeneration = wire.requiredGeneration,
                sourceRange = wire.sourceRange,
                fileHashes = wire.fileHashes,
                compilerContext = wire.compilerContext,
                oldSignature = wire.oldSignature,
                proposedSignature = wire.proposedSignature,
                proposedDeclarationHash = wire.proposedDeclarationHash,
                proposedDeclarationLength = wire.proposedDeclarationLength,
                proposedBodyHash = wire.proposedBodyHash,
                proposedBodyLength = wire.proposedBodyLength,
                declarationSlice = wire.declarationSlice,
                proposedBodySlice = wire.proposedBodySlice,
                evidence = wire.evidence,
                outboundReferences = wire.outboundReferences,
            ).wireValue()
        }
    }
}

@Serializable
@SerialName("ExactReplacementProof")
private data class ExactReplacementProofWire(
    val target: SymbolIdentity,
    val requiredGeneration: MutationSemanticGeneration,
    val sourceRange: Location,
    @SerialName("fileHashes")
    val fileHashes: List<FileHash>,
    val compilerContext: ReplacementCompilerContext,
    val oldSignature: ReplacementDeclarationSignature,
    val proposedSignature: ReplacementDeclarationSignature,
    val proposedDeclarationHash: ReplacementDeclarationSha256,
    val proposedDeclarationLength: Int,
    val proposedBodyHash: ReplacementBodySha256,
    val proposedBodyLength: Int,
    val declarationSlice: ReplacementDeclarationSlice,
    val proposedBodySlice: ReplacementSubmittedBodySlice,
    @Serializable(with = ReplacementOutboundEvidence.CompleteSerializer::class)
    val evidence: ReplacementOutboundEvidence.Complete,
    @SerialName("outboundReferences")
    val outboundReferences: List<ExactReplacementOutboundReference>,
)

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
