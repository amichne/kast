package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ReplacementDeclarationSha256(
    val value: String,
) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Replacement declaration SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }
}

@Serializable
data class ReplacementDeclarationSlice(
    @DocField(description = "UTF-16 start offset of the Kotlin declaration relative to the full proposed edit.")
    val startOffset: NonNegativeInt,
    @DocField(description = "UTF-16 end offset of the Kotlin declaration relative to the full proposed edit.")
    val endOffset: NonNegativeInt,
) {
    init {
        require(endOffset.value > startOffset.value) {
            "Replacement declaration slice end must be after its start"
        }
    }
}

@Serializable
class ExactReplacementProof private constructor(
    @DocField(description = "Exact compiler-resolved identity of the source declaration.")
    val target: SymbolIdentity,
    @DocField(description = "Semantic source generation required by this replacement proof.")
    val requiredGeneration: MutationSemanticGeneration,
    @DocField(description = "Exact full source range of the declaration to replace.")
    val sourceRange: Location,
    @DocField(description = "Exact source file hashes that bind this replacement proof.")
    @SerialName("fileHashes")
    private val storedFileHashes: List<FileHash>,
    @DocField(description = "Typed compiler-observable signature of the existing declaration.")
    val oldSignature: ReplacementDeclarationSignature,
    @DocField(description = "Typed compiler-observable signature of the proposed declaration.")
    val proposedSignature: ReplacementDeclarationSignature,
    @DocField(description = "SHA-256 of the exact full proposed edit text, including its whitespace envelope.")
    val proposedDeclarationHash: ReplacementDeclarationSha256,
    @DocField(description = "Exact UTF-16 length of the full proposed edit text, including its whitespace envelope.")
    val proposedDeclarationLength: Int,
    @DocField(description = "Exact Kotlin declaration range relative to the full proposed edit.")
    val declarationSlice: ReplacementDeclarationSlice,
    @DocField(description = "Operation-relative complete outbound-reference proof and exact occurrence cardinality.")
    @Serializable(with = ReplacementOutboundEvidence.CompleteSerializer::class)
    val evidence: ReplacementOutboundEvidence.Complete,
    @DocField(description = "Every compiler-resolved outbound reference in the proposed declaration.")
    @SerialName("outboundReferences")
    private val storedOutboundReferences: List<ExactReplacementOutboundReference>,
) {
    val fileHashes: List<FileHash>
        get() = Collections.unmodifiableList(storedFileHashes)
    val outboundReferences: List<ExactReplacementOutboundReference>
        get() = Collections.unmodifiableList(storedOutboundReferences)

    init {
        require(target.kind == SymbolKind.FUNCTION || target.kind == SymbolKind.PROPERTY) {
            "Exact replacement proof supports only function and property targets"
        }
        require(sourceRange.filePath == target.declarationFile.value) {
            "Exact replacement source range must be in the target declaration file"
        }
        require(sourceRange.startOffset <= target.declarationStartOffset.value &&
            target.declarationStartOffset.value < sourceRange.endOffset) {
            "Exact replacement source range must contain the target declaration name"
        }
        require(storedFileHashes.size == 1 && storedFileHashes.single().filePath == sourceRange.filePath) {
            "Exact replacement proof requires one hash for the exact source file"
        }
        require(oldSignature == proposedSignature) {
            "Exact replacement signatures must be equal"
        }
        require(
            (target.kind == SymbolKind.FUNCTION && oldSignature is ReplacementFunctionSignature) ||
                (target.kind == SymbolKind.PROPERTY && oldSignature is ReplacementPropertySignature),
        ) { "Exact replacement signature kind must match the target kind" }
        require(proposedDeclarationLength > 0) { "Proposed replacement declaration must not be empty" }
        require(declarationSlice.endOffset.value <= proposedDeclarationLength) {
            "Replacement declaration slice must be inside the full proposed edit"
        }
        require(evidence.cardinality.totalCount == storedOutboundReferences.size) {
            "Exact replacement cardinality must match the outbound reference count"
        }
        require(storedOutboundReferences.all { reference ->
            reference.provenance == ReplacementOccurrenceProvenance.COMPILER &&
                reference.relativeStartOffset >= declarationSlice.startOffset.value &&
                reference.relativeEndOffset <= declarationSlice.endOffset.value
        }) { "Every outbound replacement reference must have compiler provenance and an exact range" }
        require(storedOutboundReferences.map { reference ->
            reference.relativeStartOffset to reference.relativeEndOffset
        }.distinct().size == storedOutboundReferences.size) {
            "Outbound replacement references must have unique source ranges"
        }
    }

    companion object {
        fun of(
            target: SymbolIdentity,
            requiredGeneration: MutationSemanticGeneration,
            sourceRange: Location,
            fileHashes: List<FileHash>,
            oldSignature: ReplacementDeclarationSignature,
            proposedSignature: ReplacementDeclarationSignature,
            proposedDeclarationHash: ReplacementDeclarationSha256,
            proposedDeclarationLength: Int,
            declarationSlice: ReplacementDeclarationSlice,
            evidence: ReplacementOutboundEvidence.Complete,
            outboundReferences: List<ExactReplacementOutboundReference>,
        ): ExactReplacementProof = ExactReplacementProof(
            target = target,
            requiredGeneration = requiredGeneration,
            sourceRange = sourceRange,
            storedFileHashes = fileHashes.toList(),
            oldSignature = oldSignature,
            proposedSignature = proposedSignature,
            proposedDeclarationHash = proposedDeclarationHash,
            proposedDeclarationLength = proposedDeclarationLength,
            declarationSlice = declarationSlice,
            evidence = evidence,
            storedOutboundReferences = outboundReferences.toList(),
        )
    }
}
