package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementBodySha256
import io.github.amichne.kast.api.contract.result.ReplacementCompilerContext
import io.github.amichne.kast.api.contract.result.ReplacementCompilerModelGeneration
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.contract.result.ReplacementContractFailure
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementSubmittedBodySlice
import io.github.amichne.kast.api.contract.result.ReplacementCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementProofDimension
import io.github.amichne.kast.api.contract.result.ReplacementValueParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.protocol.ReplacementProofFailureEvidence
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactReplacementProofTest {
    @Test
    fun `proof snapshots outbound occurrences and proves every closed dimension`() {
        val mutableReferences = mutableListOf(outboundReference())

        val proof = proof(outboundReferences = mutableReferences)
        mutableReferences.clear()

        assertEquals(1, proof.outboundReferences.size)
        assertEquals(ReplacementProofDimension.entries, proof.evidence.dimensions)
        assertEquals(1, proof.evidence.cardinality.totalCount)
        assertNotSame(mutableReferences, proof.outboundReferences)
    }

    @Test
    fun `proof rejects outbound cardinality drift`() {
        val admission = proofAdmission(
            evidence = ReplacementOutboundEvidence.Complete.of(2),
            outboundReferences = listOf(outboundReference()),
        )
        assertEquals(
            ReplacementContractFailure.OUTBOUND_CARDINALITY_MISMATCH,
            (admission as ReplacementContractAdmission.Rejected).failure,
        )
    }

    @Test
    fun `outbound occurrence source text must match its exact range`() {
        assertThrows(IllegalArgumentException::class.java) {
            outboundReference().copy(relativeEndOffset = 6)
        }
    }

    @Test
    fun `proof requires every outbound occurrence inside the declaration slice`() {
        assertEquals(
            ReplacementContractFailure.BODY_SLICE_OUT_OF_BOUNDS,
            rejected(
                proofAdmission(
                    evidence = ReplacementOutboundEvidence.Complete.of(1),
                outboundReferences = listOf(outboundReference()),
                declarationSlice = admitted(
                    ReplacementDeclarationSlice.of(NonNegativeInt(2), NonNegativeInt(50)),
                ),
                ),
            ),
        )
    }

    @Test
    fun `limited evidence names the failed operation-relative dimension`() {
        val evidence = ReplacementProofFailureEvidence.of(
            ReplacementProofLimitation.GENERATION_CHANGED,
            knownMinimumCount = 3,
        )

        assertEquals(3, evidence.outboundEvidence.cardinality.knownMinimumCount)
        assertEquals(
            listOf(ReplacementProofDimension.SEMANTIC_GENERATION_UNCHANGED),
            evidence.outboundEvidence.dimensions,
        )
    }

    @Test
    fun `replacement result requires and snapshots one exact changed file image`() {
        val proposed = "fun greet(value: String): String = value"
        val image = exactImage(proposed)
        val mutableImages = mutableListOf(image)
        val result = admitted(ReplacementPlanResult.admit(
            edit = replacementEdit(proposed),
            proof = resultProof(proposed, image.preimage.sha256.value),
            fileImages = mutableImages,
        ))

        mutableImages.clear()

        assertEquals(listOf(image), result.fileImages)
        assertNotSame(mutableImages, result.fileImages)
    }

    @Test
    fun `replacement result preserves a whitespace envelope outside its declaration slice`() {
        val proposed = "\nfun greet(value: String): String = value\n"
        val image = exactImage(proposed)
        val proof = resultProof(
            proposed = proposed,
            fileHash = image.preimage.sha256.value,
            declarationSlice = admitted(ReplacementDeclarationSlice.of(
                NonNegativeInt(1),
                NonNegativeInt(proposed.length - 1),
            )),
        )

        val result = admitted(ReplacementPlanResult.admit(replacementEdit(proposed), proof, listOf(image)))

        assertEquals(proposed, result.edit.newText)
        assertEquals(proof.declarationSlice, result.proof.declarationSlice)
    }

    @Test
    fun `replacement result rejects missing inconsistent and unchanged exact images`() {
        val proposed = "fun greet(value: String): String = value"
        val image = exactImage(proposed)
        val proof = resultProof(proposed, image.preimage.sha256.value)

        assertEquals(
            ReplacementContractFailure.FILE_IMAGE_SET_MISMATCH,
            rejected(ReplacementPlanResult.admit(replacementEdit(proposed), proof, emptyList())),
        )
        assertEquals(
            ReplacementContractFailure.SOURCE_FILE_HASH_INVALID,
            rejected(ExactReplacementProof.admit(
                target = proof.target,
                requiredGeneration = proof.requiredGeneration,
                sourceRange = proof.sourceRange,
                fileHashes = listOf(FileHash(proof.sourceRange.filePath, "A".repeat(64))),
                compilerContext = proof.compilerContext,
                oldSignature = proof.oldSignature,
                proposedSignature = proof.proposedSignature,
                proposedDeclarationHash = proof.proposedDeclarationHash,
                proposedDeclarationLength = proof.proposedDeclarationLength,
                proposedBodyHash = proof.proposedBodyHash,
                proposedBodyLength = proof.proposedBodyLength,
                declarationSlice = proof.declarationSlice,
                proposedBodySlice = proof.proposedBodySlice,
                evidence = proof.evidence,
                outboundReferences = proof.outboundReferences,
            )),
        )
        assertEquals(
            ReplacementContractFailure.POSTIMAGE_UNCHANGED,
            rejected(ReplacementPlanResult.admit(
                replacementEdit(proposed),
                resultProof(proposed, image.preimage.sha256.value),
                listOf(
                    ExactFileImage.of(
                        filePath = image.filePath.value,
                        preimageBytes = image.preimage.copyBytes(),
                        postimageBytes = image.preimage.copyBytes(),
                    ),
                ),
            )),
        )
    }

    @Test
    fun `replacement result rejects a postimage not derived from its UTF-16 edit`() {
        val proposed = "fun greet(value: String): String = value"
        val validImage = exactImage(proposed)
        val unrelatedImage = ExactFileImage.of(
            filePath = validImage.filePath.value,
            preimageBytes = validImage.preimage.copyBytes(),
            postimageBytes = validImage.postimage.copyBytes() + "// unrelated".toByteArray(),
        )

        assertEquals(
            ReplacementContractFailure.POSTIMAGE_REPLAY_INVALID,
            rejected(ReplacementPlanResult.admit(
                edit = replacementEdit(proposed),
                proof = resultProof(proposed, validImage.preimage.sha256.value),
                fileImages = listOf(unrelatedImage),
            )),
        )
    }

    private fun proof(
        evidence: ReplacementOutboundEvidence.Complete = ReplacementOutboundEvidence.Complete.of(1),
        outboundReferences: List<ExactReplacementOutboundReference>,
        declarationSlice: ReplacementDeclarationSlice =
            admitted(ReplacementDeclarationSlice.of(NonNegativeInt(0), NonNegativeInt(50))),
    ): ExactReplacementProof = admitted(
        proofAdmission(evidence, outboundReferences, declarationSlice),
    )

    private fun proofAdmission(
        evidence: ReplacementOutboundEvidence.Complete,
        outboundReferences: List<ExactReplacementOutboundReference>,
        declarationSlice: ReplacementDeclarationSlice =
            admitted(ReplacementDeclarationSlice.of(NonNegativeInt(0), NonNegativeInt(50))),
    ): ReplacementContractAdmission<ExactReplacementProof> {
        val signature = signature()
        return ExactReplacementProof.admit(
            target = SymbolIdentity(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse("/workspace/src/Sample.kt"),
                declarationStartOffset = NonNegativeInt(12),
            ),
            requiredGeneration = MutationSemanticGeneration(7),
            sourceRange = Location(
                filePath = "/workspace/src/Sample.kt",
                startOffset = 13,
                endOffset = 40,
                startLine = 1,
                startColumn = 5,
                preview = "fun greet(value: String): String = value",
            ),
            fileHashes = listOf(FileHash("/workspace/src/Sample.kt", "1".repeat(64))),
            compilerContext = ReplacementCompilerContext.of(
                emptyMap(),
                admitted(ReplacementCompilerModelGeneration.parse(1)),
            ),
            oldSignature = signature,
            proposedSignature = signature,
            proposedDeclarationHash = admitted(ReplacementDeclarationSha256.parse("0".repeat(64))),
            proposedDeclarationLength = 50,
            proposedBodyHash = admitted(ReplacementBodySha256.parse("0".repeat(64))),
            proposedBodyLength = 50,
            declarationSlice = declarationSlice,
            proposedBodySlice = admitted(
                ReplacementSubmittedBodySlice.of(NonNegativeInt(0), NonNegativeInt(50)),
            ),
            evidence = evidence,
            outboundReferences = outboundReferences,
        )
    }

    private fun signature(): ReplacementFunctionSignature = ReplacementFunctionSignature.of(
        name = "greet",
        receiverType = null,
        contextReceiverTypes = emptyList(),
        typeParameters = emptyList(),
        valueParameters = listOf(
            ReplacementValueParameterSignature(
                name = "value",
                type = "kotlin.String",
                vararg = false,
                hasDefaultValue = false,
                noinline = false,
                crossinline = false,
            ),
        ),
        returnType = "kotlin.String",
        visibility = ReplacementVisibility.PUBLIC,
        modality = ReplacementModality.FINAL,
        hasStableParameterNames = true,
        suspend = false,
        operator = false,
        inline = false,
        override = false,
        infix = false,
        static = false,
        tailrec = false,
        external = false,
        expect = false,
        actual = false,
    )

    private fun exactImage(proposed: String): ExactFileImage {
        val preimage = "head" + "x".repeat(36) + "tail"
        return ExactFileImage.of(
            filePath = "/workspace/src/Sample.kt",
            preimageBytes = preimage.toByteArray(),
            postimageBytes = preimage.replaceRange(4, 40, proposed).toByteArray(),
        )
    }

    private fun replacementEdit(proposed: String): TextEdit = TextEdit(
        filePath = "/workspace/src/Sample.kt",
        startOffset = 4,
        endOffset = 40,
        newText = proposed,
    )

    private fun resultProof(
        proposed: String,
        fileHash: String,
        declarationSlice: ReplacementDeclarationSlice =
            admitted(
                ReplacementDeclarationSlice.of(NonNegativeInt(0), NonNegativeInt(proposed.length)),
            ),
    ): ExactReplacementProof {
        val signature = signature()
        return admitted(ExactReplacementProof.admit(
            target = SymbolIdentity(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse("/workspace/src/Sample.kt"),
                declarationStartOffset = NonNegativeInt(0),
            ),
            requiredGeneration = MutationSemanticGeneration(7),
            sourceRange = Location(
                filePath = "/workspace/src/Sample.kt",
                startOffset = 4,
                endOffset = 40,
                startLine = 1,
                startColumn = 5,
                preview = "fun greet(value: String): String = value",
            ),
            fileHashes = listOf(FileHash("/workspace/src/Sample.kt", fileHash)),
            compilerContext = ReplacementCompilerContext.of(
                emptyMap(),
                admitted(ReplacementCompilerModelGeneration.parse(1)),
            ),
            oldSignature = signature,
            proposedSignature = signature,
            proposedDeclarationHash = admitted(
                ReplacementDeclarationSha256.parse(FileHashing.sha256(proposed)),
            ),
            proposedDeclarationLength = proposed.length,
            proposedBodyHash = admitted(ReplacementBodySha256.parse(FileHashing.sha256(proposed))),
            proposedBodyLength = proposed.length,
            declarationSlice = declarationSlice,
            proposedBodySlice = admitted(
                ReplacementSubmittedBodySlice.of(
                    NonNegativeInt(declarationSlice.startOffset.value),
                    NonNegativeInt(declarationSlice.endOffset.value),
                ),
            ),
            evidence = ReplacementOutboundEvidence.Complete.of(0),
            outboundReferences = emptyList(),
        ))
    }

    private fun <Value> admitted(admission: ReplacementContractAdmission<Value>): Value =
        (admission as ReplacementContractAdmission.Admitted).value

    private fun rejected(admission: ReplacementContractAdmission<*>): ReplacementContractFailure =
        (admission as ReplacementContractAdmission.Rejected).failure

    private fun outboundReference(): ExactReplacementOutboundReference = ExactReplacementOutboundReference(
        relativeStartOffset = 1,
        relativeEndOffset = 7,
        sourceText = "String",
        resolvedTarget = ReplacementOutboundTarget.External(
            fqName = "kotlin.String",
            kind = io.github.amichne.kast.api.contract.result.ReplacementCompilerSymbolKind.CLASS,
            signature = ReplacementCompilerTargetSignature("class|kotlin.String"),
        ),
        provenance = ReplacementOccurrenceProvenance.COMPILER,
    )
}
