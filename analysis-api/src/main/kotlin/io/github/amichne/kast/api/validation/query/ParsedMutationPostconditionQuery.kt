package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.ValidationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.CharacterCodingException

data class ParsedMutationPostconditionQuery(
    val authority: ParsedMutationPostconditionAuthority,
)

sealed interface ParsedMutationPostconditionAuthority {
    data class Rename(
        val proof: ExactRenameProof,
        val edits: List<TextEdit>,
        val images: List<ExactFileImage>,
    ) : ParsedMutationPostconditionAuthority

    data class Replacement(
        val proof: ExactReplacementProof,
        val edit: TextEdit,
        val images: List<ExactFileImage>,
    ) : ParsedMutationPostconditionAuthority

    data class AddFile(
        val proof: ExactAddFileProof,
        val postimage: ExactByteImage,
    ) : ParsedMutationPostconditionAuthority

    data class AddDeclaration(
        val proof: ExactAddDeclarationProof,
        val image: ExactFileImage,
        val proposedDeclaration: String,
    ) : ParsedMutationPostconditionAuthority
}

fun MutationPostconditionQuery.parsed(): ParsedMutationPostconditionQuery = mutationValidationBoundary {
    ParsedMutationPostconditionQuery(
        authority = when (val supplied = authority) {
            is MutationPostconditionAuthority.Rename -> {
                val images = supplied.images.toList()
                RenameResult.of(
                    edits = supplied.edits,
                    fileHashes = images.map { image ->
                        io.github.amichne.kast.api.contract.FileHash(
                            filePath = image.filePath.value,
                            hash = image.preimage.sha256.value,
                        )
                    },
                    fileImages = images,
                    proof = supplied.proof,
                )
                ParsedMutationPostconditionAuthority.Rename(
                    proof = supplied.proof,
                    edits = supplied.edits.toList(),
                    images = images,
                )
            }

            is MutationPostconditionAuthority.Replacement -> {
                val images = supplied.images.toList()
                ReplacementPlanResult.of(supplied.edit, supplied.proof, images)
                ParsedMutationPostconditionAuthority.Replacement(
                    proof = supplied.proof,
                    edit = supplied.edit,
                    images = images,
                )
            }

            is MutationPostconditionAuthority.AddFile -> {
                val content = strictUtf8(supplied.postimage.copyBytes())
                require('\r' !in content && '\uFEFF' !in content) {
                    "Add-file verifier postimage must be normalized Kotlin UTF-8 text"
                }
                AddFilePlanResult.of(content, supplied.proof)
                ParsedMutationPostconditionAuthority.AddFile(supplied.proof, supplied.postimage)
            }

            is MutationPostconditionAuthority.AddDeclaration -> {
                val proposal = exactAddDeclarationProposal(supplied.proof, supplied.image)
                AddDeclarationPlanResult.of(proposal, supplied.image, supplied.proof)
                ParsedMutationPostconditionAuthority.AddDeclaration(
                    proof = supplied.proof,
                    image = supplied.image,
                    proposedDeclaration = proposal,
                )
            }
        },
    )
}

private fun exactAddDeclarationProposal(
    proof: ExactAddDeclarationProof,
    image: ExactFileImage,
): String {
    require(image.filePath.value == proof.targetPath.value)
    require(image.preimage.sha256.value == proof.targetPreimageSha256.value)
    require(image.postimage.sha256.value == proof.postimageSha256.value)
    val preimage = image.preimage.copyBytes()
    val postimage = image.postimage.copyBytes()
    require(postimage.size > preimage.size && postimage.copyOfRange(0, preimage.size).contentEquals(preimage)) {
        "Add-declaration verifier image must retain the exact target preimage prefix"
    }
    val normalizedPreimage = strictUtf8(preimage)
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    require(proof.insertion.offset.value == normalizedPreimage.length)
    val separator = when {
        normalizedPreimage.isEmpty() || normalizedPreimage.endsWith("\n\n") -> ""
        normalizedPreimage.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    val append = strictUtf8(postimage.copyOfRange(preimage.size, postimage.size))
    require(append.startsWith(separator) && append.endsWith('\n')) {
        "Add-declaration verifier image must use the proven FILE_BOTTOM newline policy"
    }
    val proposal = append.removePrefix(separator).dropLast(1)
    require(proposal.isNotBlank() && '\r' !in proposal && '\uFEFF' !in proposal && !proposal.endsWith('\n'))
    return proposal
}

private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private inline fun <T> mutationValidationBoundary(block: () -> T): T = try {
    block()
} catch (failure: ValidationException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw ValidationException(failure.message ?: "Invalid mutation postcondition authority")
} catch (failure: CharacterCodingException) {
    throw ValidationException("Mutation postcondition image must contain strict UTF-8")
}
