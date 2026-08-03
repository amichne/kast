package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
class AddFilePlanResult private constructor(
    @DocField(description = "Exact proposed Kotlin source content.")
    val proposedContent: String,
    @DocField(description = "Exact UTF-8 postimage authorized for the absent target.")
    val postimage: ExactByteImage,
    @DocField(description = "Complete compiler-backed add-file proof.")
    val proof: ExactAddFileProof,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(postimage.copyBytes().contentEquals(strictAdditionUtf8Bytes(proposedContent))) {
            "Add-file proposed content must equal the exact UTF-8 postimage"
        }
        require(postimage.sha256.value == proof.postimageSha256.value) {
            "Add-file postimage must match the proven postimage SHA-256"
        }
        validateRelativeAdditionRanges(
            contentLength = proposedContent.length,
            declarations = proof.declarations,
            outboundEvidence = proof.outboundEvidence,
        )
    }

    companion object {
        fun of(proposedContent: String, proof: ExactAddFileProof): AddFilePlanResult = AddFilePlanResult(
            proposedContent = proposedContent,
            postimage = ExactByteImage.of(strictAdditionUtf8Bytes(proposedContent)),
            proof = proof,
        )
    }
}

internal fun strictAdditionUtf8Bytes(value: String): ByteArray {
    val encoded = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    return ByteArray(encoded.remaining()).also(encoded::get)
}

internal fun validateRelativeAdditionRanges(
    contentLength: Int,
    declarations: List<AdditionTopLevelDeclaration>,
    outboundEvidence: ExactAdditionOutboundEvidence,
) {
    require(declarations.all { it.relativeRange.endOffset.value <= contentLength }) {
        "Every addition declaration range must be contained by the exact proposed content"
    }
    require(outboundEvidence.occurrences.all { it.range.endOffset.value <= contentLength }) {
        "Every addition outbound range must be contained by the exact proposed content"
    }
}
