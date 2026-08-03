package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
class AddDeclarationPlanResult private constructor(
    @DocField(description = "Exact normalized LF Kotlin declaration supplied by the caller.")
    val proposedDeclaration: String,
    @DocField(description = "Exact decoded postimage content, including its original line-separator form.")
    val proposedContent: String,
    @DocField(description = "Exact target preimage and authorized postimage.")
    val image: ExactFileImage,
    @DocField(description = "Complete compiler-backed add-declaration proof.")
    val proof: ExactAddDeclarationProof,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(image.filePath.value == proof.targetPath.value) {
            "Add-declaration image path must match the proven target path"
        }
        require(image.preimage.sha256.value == proof.targetPreimageSha256.value) {
            "Add-declaration preimage must match the proven target preimage SHA-256"
        }
        require(image.postimage.sha256.value == proof.postimageSha256.value) {
            "Add-declaration postimage must match the proven postimage SHA-256"
        }
        val normalizedPreimage = normalizedIdeaText(image.preimage.copyBytes())
        require(proof.insertion.offset.value == normalizedPreimage.length) {
            "Compiler FILE_BOTTOM insertion must equal the exact normalized document length"
        }
        require('\r' !in proposedDeclaration && proposedDeclaration.isNotBlank() &&
            proposedDeclaration == proposedDeclaration.trimEnd('\n')
        ) { "The proposed declaration must be non-blank normalized LF content without a final line break" }
        val separator = when {
            normalizedPreimage.isEmpty() || normalizedPreimage.endsWith("\n\n") -> ""
            normalizedPreimage.endsWith('\n') -> "\n"
            else -> "\n\n"
        }
        val exactAppend = strictAdditionUtf8Bytes(separator + proposedDeclaration + "\n")
        val exactExpectedPostimage = image.preimage.copyBytes() + exactAppend
        require(image.postimage.copyBytes().contentEquals(exactExpectedPostimage)) {
            "Add-declaration postimage must preserve every preimage byte and use the exact FILE_BOTTOM LF append algorithm"
        }
        validateRelativeAdditionRanges(
            contentLength = proposedDeclaration.length,
            declarations = listOf(proof.declaration),
            outboundEvidence = proof.outboundEvidence,
        )
    }

    companion object {
        fun of(
            proposedDeclaration: String,
            image: ExactFileImage,
            proof: ExactAddDeclarationProof,
        ): AddDeclarationPlanResult = AddDeclarationPlanResult(
            proposedDeclaration = proposedDeclaration,
            proposedContent = strictUtf8Text(image.postimage.copyBytes()),
            image = image,
            proof = proof,
        )
    }
}

private fun normalizedIdeaText(bytes: ByteArray): String = strictUtf8Text(bytes)
    .removePrefix("\uFEFF")
    .replace("\r\n", "\n")
    .replace('\r', '\n')

private fun strictUtf8Text(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()
