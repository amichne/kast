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
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactReplacementProofTest {
    private val json = Json { explicitNulls = false }

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
        assertThrows(IllegalArgumentException::class.java) {
            proof(
                evidence = ReplacementOutboundEvidence.Complete.of(2),
                outboundReferences = listOf(outboundReference()),
            )
        }
    }

    @Test
    fun `outbound occurrence source text must match its exact range`() {
        assertThrows(IllegalArgumentException::class.java) {
            outboundReference().copy(relativeEndOffset = 6)
        }
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
        val result = ReplacementPlanResult.of(
            edit = replacementEdit(proposed),
            proof = resultProof(proposed, image.preimage.sha256.value),
            fileImages = mutableImages,
        )

        mutableImages.clear()

        assertEquals(listOf(image), result.fileImages)
        assertNotSame(mutableImages, result.fileImages)
    }

    @Test
    fun `replacement result rejects missing inconsistent and unchanged exact images`() {
        val proposed = "fun greet(value: String): String = value"
        val image = exactImage(proposed)
        val proof = resultProof(proposed, image.preimage.sha256.value)

        assertThrows(IllegalArgumentException::class.java) {
            ReplacementPlanResult.of(replacementEdit(proposed), proof, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplacementPlanResult.of(
                replacementEdit(proposed),
                resultProof(proposed, "A".repeat(64)),
                listOf(image),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val unchanged = ExactFileImage.of(
                filePath = image.filePath.value,
                preimageBytes = image.preimage.copyBytes(),
                postimageBytes = image.preimage.copyBytes(),
            )
            ReplacementPlanResult.of(
                replacementEdit(proposed),
                resultProof(proposed, unchanged.preimage.sha256.value),
                listOf(unchanged),
            )
        }
    }

    @Test
    fun `deserialization rejects a replacement result without exact file images`() {
        val proposed = "fun greet(value: String): String = value"
        val image = exactImage(proposed)
        val valid = ReplacementPlanResult.of(
            edit = replacementEdit(proposed),
            proof = resultProof(proposed, image.preimage.sha256.value),
            fileImages = listOf(image),
        )
        val encoded = json.encodeToJsonElement(ReplacementPlanResult.serializer(), valid).jsonObject
        val missingImages = JsonObject(encoded.filterKeys { key -> key != "fileImages" })

        assertThrows(Exception::class.java) {
            json.decodeFromJsonElement(ReplacementPlanResult.serializer(), missingImages)
        }
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

        assertThrows(IllegalArgumentException::class.java) {
            ReplacementPlanResult.of(
                edit = replacementEdit(proposed),
                proof = resultProof(proposed, validImage.preimage.sha256.value),
                fileImages = listOf(unrelatedImage),
            )
        }
    }

    @Test
    fun `replacement result deserialization rejects a postimage not derived from its UTF-16 edit`() {
        val proposed = "fun greet(value: String): String = value"
        val validImage = exactImage(proposed)
        val valid = ReplacementPlanResult.of(
            edit = replacementEdit(proposed),
            proof = resultProof(proposed, validImage.preimage.sha256.value),
            fileImages = listOf(validImage),
        )
        val unrelatedImage = ExactFileImage.of(
            filePath = validImage.filePath.value,
            preimageBytes = validImage.preimage.copyBytes(),
            postimageBytes = validImage.postimage.copyBytes() + "// unrelated".toByteArray(),
        )
        val encoded = json.encodeToJsonElement(ReplacementPlanResult.serializer(), valid).jsonObject
        val malformed = JsonObject(
            encoded + (
                "fileImages" to JsonArray(
                    listOf(json.encodeToJsonElement(ExactFileImage.serializer(), unrelatedImage)),
                )
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromJsonElement(ReplacementPlanResult.serializer(), malformed)
        }
    }

    private fun proof(
        evidence: ReplacementOutboundEvidence.Complete = ReplacementOutboundEvidence.Complete.of(1),
        outboundReferences: List<ExactReplacementOutboundReference>,
    ): ExactReplacementProof {
        val signature = signature()
        return ExactReplacementProof.of(
            target = SymbolIdentity(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse("/workspace/src/Sample.kt"),
                declarationStartOffset = NonNegativeInt(12),
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
            fileHashes = listOf(FileHash("/workspace/src/Sample.kt", "source-hash")),
            oldSignature = signature,
            proposedSignature = signature,
            proposedDeclarationHash = ReplacementDeclarationSha256("0".repeat(64)),
            proposedDeclarationLength = 50,
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

    private fun resultProof(proposed: String, fileHash: String): ExactReplacementProof {
        val signature = signature()
        return ExactReplacementProof.of(
            target = SymbolIdentity(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse("/workspace/src/Sample.kt"),
                declarationStartOffset = NonNegativeInt(12),
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
            oldSignature = signature,
            proposedSignature = signature,
            proposedDeclarationHash = ReplacementDeclarationSha256(FileHashing.sha256(proposed)),
            proposedDeclarationLength = proposed.length,
            evidence = ReplacementOutboundEvidence.Complete.of(0),
            outboundReferences = emptyList(),
        )
    }

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
