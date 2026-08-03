package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ExactRenameOccurrence
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.RenameOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ResultCardinality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenameResultTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `affected files derive from distinct edit paths in edit order`() {
        val edits = listOf(
            edit("/workspace/src/First.kt", startOffset = 1),
            edit("/workspace/src/First.kt", startOffset = 10),
            edit("/workspace/src/Second.kt", startOffset = 2),
        )
        val fileImages = imagesFor(edits)
        val result = RenameResult.of(
            edits = edits,
            fileHashes = hashesFor(fileImages),
            fileImages = fileImages,
            proof = proof(edits),
        )

        assertEquals(
            listOf("/workspace/src/First.kt", "/workspace/src/Second.kt"),
            result.affectedFiles,
        )
    }

    @Test
    fun `factory snapshots mutable plan collections`() {
        val originalEdit = edit("/workspace/src/Original.kt", startOffset = 1)
        val edits = mutableListOf(originalEdit)
        val fileImages = imagesFor(edits).toMutableList()
        val fileHashes = hashesFor(fileImages).toMutableList()
        val result = RenameResult.of(
            edits = edits,
            fileHashes = fileHashes,
            fileImages = fileImages,
            proof = proof(edits),
        )

        edits += edit("/workspace/src/AddedLater.kt", startOffset = 2)
        fileHashes.clear()
        fileImages.clear()

        assertNotSame(edits, result.edits)
        assertNotSame(fileHashes, result.fileHashes)
        assertNotSame(fileImages, result.fileImages)
        assertEquals(listOf(originalEdit), result.edits)
        assertEquals(1, result.fileHashes.size)
        assertEquals(1, result.fileImages.size)
        assertEquals(listOf(originalEdit.filePath), result.affectedFiles)
    }

    @Test
    fun `equivalent rename results retain structural value semantics`() {
        val edits = listOf(edit("/workspace/src/Value.kt", startOffset = 1))
        val fileImages = imagesFor(edits)
        val fileHashes = hashesFor(fileImages)
        val first = RenameResult.of(
            edits = edits,
            fileHashes = fileHashes,
            fileImages = fileImages,
            proof = proof(edits),
        )
        val equivalent = RenameResult.of(
            edits = edits.toList(),
            fileHashes = fileHashes.toList(),
            fileImages = fileImages.toList(),
            proof = proof(edits),
        )
        val differentEdits = listOf(edit("/workspace/src/Different.kt", startOffset = 1))
        val differentImages = imagesFor(differentEdits)
        val different = RenameResult.of(
            edits = differentEdits,
            fileHashes = hashesFor(differentImages),
            fileImages = differentImages,
            proof = proof(differentEdits),
        )

        assertEquals(first, equivalent)
        assertEquals(first.hashCode(), equivalent.hashCode())
        assertNotEquals(first, different)
        assertEquals(
            "RenameResult(" +
                "edits=${first.edits}, " +
                "fileHashes=${first.fileHashes}, " +
                "affectedFiles=${first.affectedFiles}, " +
                "fileImages=${first.fileImages}, " +
                "proof=${first.proof}, " +
                "searchScope=${first.searchScope}, " +
                "schemaVersion=${first.schemaVersion}" +
                ")",
            first.toString(),
        )
    }

    @Test
    fun `deserialization rejects affected files that contradict edit paths`() {
        val edits = listOf(edit("/workspace/src/Actual.kt", startOffset = 1))
        val fileImages = imagesFor(edits)
        val valid = RenameResult.of(
            edits = edits,
            fileHashes = hashesFor(fileImages),
            fileImages = fileImages,
            proof = proof(edits),
        )
        val validJson = json.encodeToJsonElement(RenameResult.serializer(), valid).jsonObject
        val malformedJson = JsonObject(
            validJson + ("affectedFiles" to JsonArray(listOf(JsonPrimitive("/workspace/src/Fabricated.kt")))),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromJsonElement(RenameResult.serializer(), malformedJson)
        }

        assertTrue(checkNotNull(failure.message).contains("affectedFiles"))
    }

    @Test
    fun `deserialization rejects a rename result without exact file images`() {
        val edits = listOf(edit("/workspace/src/Missing.kt", startOffset = 1))
        val fileImages = imagesFor(edits)
        val valid = RenameResult.of(
            edits = edits,
            fileHashes = hashesFor(fileImages),
            fileImages = fileImages,
            proof = proof(edits),
        )
        val validJson = json.encodeToJsonElement(RenameResult.serializer(), valid).jsonObject
        val missingImages = JsonObject(validJson.filterKeys { key -> key != "fileImages" })

        assertThrows(Exception::class.java) {
            json.decodeFromJsonElement(RenameResult.serializer(), missingImages)
        }
    }

    @Test
    fun `rename result rejects missing inconsistent or unchanged exact images`() {
        val edits = listOf(edit("/workspace/src/Exact.kt", startOffset = 1))
        val image = imagesFor(edits).single()

        assertThrows(IllegalArgumentException::class.java) {
            RenameResult.of(
                edits = edits,
                fileHashes = hashesFor(listOf(image)),
                fileImages = emptyList(),
                proof = proof(edits),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenameResult.of(
                edits = edits,
                fileHashes = listOf(FileHash(image.filePath.value, "A".repeat(64))),
                fileImages = listOf(image),
                proof = proof(edits),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val unchanged = ExactFileImage.of(
                filePath = image.filePath.value,
                preimageBytes = image.preimage.copyBytes(),
                postimageBytes = image.preimage.copyBytes(),
            )
            RenameResult.of(
                edits = edits,
                fileHashes = hashesFor(listOf(unchanged)),
                fileImages = listOf(unchanged),
                proof = proof(edits),
            )
        }
    }

    @Test
    fun `rename result rejects a postimage not derived from its UTF-16 edits`() {
        val edit = edit("/workspace/src/Replay.kt", startOffset = 1)
        val preimage = "xold-name-tail".toByteArray()
        val unrelatedPostimage = "xrenamed-tail-unrelated".toByteArray()
        val image = ExactFileImage.of(edit.filePath, preimage, unrelatedPostimage)

        assertThrows(IllegalArgumentException::class.java) {
            RenameResult.of(
                edits = listOf(edit),
                fileHashes = hashesFor(listOf(image)),
                fileImages = listOf(image),
                proof = proof(listOf(edit)),
            )
        }
    }

    @Test
    fun `rename result deserialization rejects a postimage not derived from its UTF-16 edits`() {
        val edit = edit("/workspace/src/ReplayWire.kt", startOffset = 1)
        val preimageText = "xold-name-tail"
        val preimage = preimageText.toByteArray()
        val validImage = ExactFileImage.of(
            filePath = edit.filePath,
            preimageBytes = preimage,
            postimageBytes = preimageText
                .replaceRange(edit.startOffset, edit.endOffset, edit.newText)
                .toByteArray(),
        )
        val valid = RenameResult.of(
            edits = listOf(edit),
            fileHashes = hashesFor(listOf(validImage)),
            fileImages = listOf(validImage),
            proof = proof(listOf(edit)),
        )
        val unrelatedImage = ExactFileImage.of(
            filePath = edit.filePath,
            preimageBytes = preimage,
            postimageBytes = preimageText
                .replaceRange(edit.startOffset, edit.endOffset, edit.newText)
                .plus("-unrelated")
                .toByteArray(),
        )
        val encoded = json.encodeToJsonElement(RenameResult.serializer(), valid).jsonObject
        val malformed = JsonObject(
            encoded + (
                "fileImages" to JsonArray(
                    listOf(json.encodeToJsonElement(ExactFileImage.serializer(), unrelatedImage)),
                )
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromJsonElement(RenameResult.serializer(), malformed)
        }
    }

    private fun edit(filePath: String, startOffset: Int): TextEdit = TextEdit(
        filePath = filePath,
        startOffset = startOffset,
        endOffset = startOffset + 1,
        newText = "renamed",
    )

    private fun imagesFor(edits: List<TextEdit>): List<ExactFileImage> = edits
        .groupBy(TextEdit::filePath)
        .map { (filePath, fileEdits) ->
            val preimage = "x".repeat(fileEdits.maxOf(TextEdit::endOffset) + 1)
            val postimage = fileEdits
                .sortedByDescending(TextEdit::startOffset)
                .fold(preimage) { current, edit ->
                    current.replaceRange(edit.startOffset, edit.endOffset, edit.newText)
                }
            ExactFileImage.of(
                filePath = filePath,
                preimageBytes = preimage.toByteArray(),
                postimageBytes = postimage.toByteArray(),
            )
        }

    private fun hashesFor(fileImages: List<ExactFileImage>): List<FileHash> = fileImages.map { image ->
        FileHash(image.filePath.value, image.preimage.sha256.value)
    }

    private fun proof(edits: List<TextEdit>): ExactRenameProof {
        val declaration = edits.first()
        val target = SymbolIdentity(
            fqName = "sample.target",
            kind = SymbolKind.FUNCTION,
            declarationFile = NormalizedPath.parse(declaration.filePath),
            declarationStartOffset = NonNegativeInt(declaration.startOffset),
        )
        val occurrences = edits.drop(1).map { edit ->
            ExactRenameOccurrence(
                reference = ReferenceOccurrence(
                    location = Location(
                        filePath = edit.filePath,
                        startOffset = edit.startOffset,
                        endOffset = edit.endOffset,
                        startLine = 1,
                        startColumn = 1,
                        preview = "renamed",
                    ),
                    containingSymbol = ContainingSymbolEvidence.TopLevel,
                ),
                resolvedTarget = target,
                provenance = RenameOccurrenceProvenance.COMPILER,
            )
        }
        return ExactRenameProof.of(
            target = target,
            requiredGeneration = MutationSemanticGeneration(1),
            evidence = RelationshipResultEvidence.Complete(
                cardinality = ResultCardinality.Exact(occurrences.size),
                coverage = RelationshipSearchCoverage.complete(),
            ),
            occurrences = occurrences,
        )
    }
}
