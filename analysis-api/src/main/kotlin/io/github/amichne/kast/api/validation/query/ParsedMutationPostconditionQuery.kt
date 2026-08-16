package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.result.AddDeclarationPlanResult
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.contract.result.ReplacementContractFailure
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.protocol.ValidationException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class ParsedMutationPostconditionQuery(
    val authority: ParsedMutationPostconditionAuthority,
)

sealed interface ParsedMutationPostconditionAuthority {
    data class Rename(
        val plan: RenameResult,
    ) : ParsedMutationPostconditionAuthority {
        val proof: ExactRenameProof
            get() = plan.proof
        val edits: List<TextEdit>
            get() = plan.edits
        val images: List<ExactFileImage>
            get() = plan.fileImages
    }

    data class Replacement(
        val plan: ReplacementPlanResult,
    ) : ParsedMutationPostconditionAuthority {
        val proof: ExactReplacementProof
            get() = plan.proof
        val edit: TextEdit
            get() = plan.edit
        val images: List<ExactFileImage>
            get() = plan.fileImages
    }

    data class AddFile(
        val plan: AddFilePlanResult,
    ) : ParsedMutationPostconditionAuthority {
        val proof: ExactAddFileProof
            get() = plan.proof
        val postimage: ExactByteImage
            get() = plan.postimage
    }

    data class AddDeclaration(
        val plan: AddDeclarationPlanResult,
    ) : ParsedMutationPostconditionAuthority {
        val proof: ExactAddDeclarationProof
            get() = plan.proof
        val image: ExactFileImage
            get() = plan.image
        val proposedDeclaration: String
            get() = plan.proposedDeclaration
    }
}

private enum class MutationPostconditionAdmissionFailure {
    RENAME_AUTHORITY_INVALID,
    ADD_FILE_POSTIMAGE_INVALID,
    ADD_FILE_AUTHORITY_INVALID,
    ADD_DECLARATION_IMAGE_MISMATCH,
    ADD_DECLARATION_PREIMAGE_MISMATCH,
    ADD_DECLARATION_POSTIMAGE_MISMATCH,
    ADD_DECLARATION_PREFIX_MISMATCH,
    ADD_DECLARATION_INSERTION_MISMATCH,
    ADD_DECLARATION_APPEND_MISMATCH,
    ADD_DECLARATION_PROPOSAL_INVALID,
    IMAGE_NOT_STRICT_UTF8,
}

private sealed interface MutationPostconditionAdmission<out Value> {
    data class Admitted<Value>(
        val value: Value,
    ) : MutationPostconditionAdmission<Value>

    data class Rejected(
        val failure: MutationPostconditionAdmissionFailure,
    ) : MutationPostconditionAdmission<Nothing>

    data class ReplacementRejected(
        val failure: ReplacementContractFailure,
    ) : MutationPostconditionAdmission<Nothing>
}

/**
 * Proof transition: [MutationPostconditionQuery] -> [ParsedMutationPostconditionQuery].
 *
 * Establishes operation-specific exact mutation authority and retains the admitted operation plan
 * instead of discarding its proof. Expected rejection is the finite
 * [MutationPostconditionAdmissionFailure] family projected to [ValidationException] at this public
 * parsing boundary. Raw mutation fields are extracted only while constructing operation-specific
 * parsed authority.
 */
fun MutationPostconditionQuery.parsed(): ParsedMutationPostconditionQuery = when (
    val admission = admitMutationPostcondition()
) {
    is MutationPostconditionAdmission.Admitted -> admission.value
    is MutationPostconditionAdmission.Rejected -> throw ValidationException(
        message = "Invalid mutation postcondition authority",
        details = mapOf("failure" to admission.failure.name),
    )
    is MutationPostconditionAdmission.ReplacementRejected -> throw ValidationException(
        message = "Invalid exact replacement postcondition authority",
        details = mapOf("failure" to admission.failure.name),
    )
}

/**
 * Proof transition: [MutationPostconditionQuery] -> [MutationPostconditionAdmission] of
 * [ParsedMutationPostconditionQuery].
 *
 * Establishes one retained operation-specific plan. Failure is a closed admission variant. Raw
 * query fields are extracted only inside this host-neutral validation boundary.
 */
private fun MutationPostconditionQuery.admitMutationPostcondition():
    MutationPostconditionAdmission<ParsedMutationPostconditionQuery> {
    val parsedAuthority = when (val supplied = authority) {
        is MutationPostconditionAuthority.Rename -> {
            val images = supplied.images.toList()
            val plan = try {
                RenameResult.of(
                    edits = supplied.edits,
                    fileHashes = images.map { image ->
                        FileHash(
                            filePath = image.filePath.value,
                            hash = image.preimage.sha256.value,
                        )
                    },
                    fileImages = images,
                    proof = supplied.proof,
                )
            } catch (_: IllegalArgumentException) {
                return MutationPostconditionAdmission.Rejected(
                    MutationPostconditionAdmissionFailure.RENAME_AUTHORITY_INVALID,
                )
            }
            ParsedMutationPostconditionAuthority.Rename(plan)
        }

        is MutationPostconditionAuthority.Replacement -> {
            val plan = when (
                val admission = ReplacementPlanResult.admit(
                    supplied.edit,
                    supplied.proof,
                    supplied.images,
                )
            ) {
                is ReplacementContractAdmission.Admitted -> admission.value
                is ReplacementContractAdmission.Rejected ->
                    return MutationPostconditionAdmission.ReplacementRejected(admission.failure)
            }
            ParsedMutationPostconditionAuthority.Replacement(plan)
        }

        is MutationPostconditionAuthority.AddFile -> {
            val content = when (val admission = strictUtf8(supplied.postimage.copyBytes())) {
                is StrictUtf8Admission.Admitted -> admission.text
                StrictUtf8Admission.Rejected -> return MutationPostconditionAdmission.Rejected(
                    MutationPostconditionAdmissionFailure.IMAGE_NOT_STRICT_UTF8,
                )
            }
            if ('\r' in content || '\uFEFF' in content) {
                return MutationPostconditionAdmission.Rejected(
                    MutationPostconditionAdmissionFailure.ADD_FILE_POSTIMAGE_INVALID,
                )
            }
            val plan = try {
                AddFilePlanResult.of(content, supplied.proof)
            } catch (_: IllegalArgumentException) {
                return MutationPostconditionAdmission.Rejected(
                    MutationPostconditionAdmissionFailure.ADD_FILE_AUTHORITY_INVALID,
                )
            }
            ParsedMutationPostconditionAuthority.AddFile(plan)
        }

        is MutationPostconditionAuthority.AddDeclaration -> {
            val proposal = when (
                val admission = exactAddDeclarationProposal(supplied.proof, supplied.image)
            ) {
                is AddDeclarationProposalAdmission.Admitted -> admission.proposal
                is AddDeclarationProposalAdmission.Rejected ->
                    return MutationPostconditionAdmission.Rejected(admission.failure)
            }
            val plan = try {
                AddDeclarationPlanResult.of(proposal, supplied.image, supplied.proof)
            } catch (_: IllegalArgumentException) {
                return MutationPostconditionAdmission.Rejected(
                    MutationPostconditionAdmissionFailure.ADD_DECLARATION_PROPOSAL_INVALID,
                )
            }
            ParsedMutationPostconditionAuthority.AddDeclaration(plan)
        }
    }
    return MutationPostconditionAdmission.Admitted(
        ParsedMutationPostconditionQuery(parsedAuthority),
    )
}

private sealed interface AddDeclarationProposalAdmission {
    data class Admitted(
        val proposal: String,
    ) : AddDeclarationProposalAdmission

    data class Rejected(
        val failure: MutationPostconditionAdmissionFailure,
    ) : AddDeclarationProposalAdmission
}

/**
 * Proof transition: [ExactAddDeclarationProof] plus [ExactFileImage] ->
 * [AddDeclarationProposalAdmission].
 *
 * Establishes the exact appended declaration and image binding. Failure is a closed
 * [MutationPostconditionAdmissionFailure]. Raw bytes are extracted only inside this parser.
 */
private fun exactAddDeclarationProposal(
    proof: ExactAddDeclarationProof,
    image: ExactFileImage,
): AddDeclarationProposalAdmission {
    if (image.filePath.value != proof.targetPath.value) {
        return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_IMAGE_MISMATCH,
        )
    }
    if (image.preimage.sha256.value != proof.targetPreimageSha256.value) {
        return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_PREIMAGE_MISMATCH,
        )
    }
    if (image.postimage.sha256.value != proof.postimageSha256.value) {
        return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_POSTIMAGE_MISMATCH,
        )
    }
    val preimage = image.preimage.copyBytes()
    val postimage = image.postimage.copyBytes()
    if (
        postimage.size <= preimage.size ||
        !postimage.copyOfRange(0, preimage.size).contentEquals(preimage)
    ) {
        return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_PREFIX_MISMATCH,
        )
    }
    val normalizedPreimage = when (val admission = strictUtf8(preimage)) {
        is StrictUtf8Admission.Admitted -> admission.text
        StrictUtf8Admission.Rejected -> return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.IMAGE_NOT_STRICT_UTF8,
        )
    }.removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    if (proof.insertion.offset.value != normalizedPreimage.length) {
        return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_INSERTION_MISMATCH,
        )
    }
    val separator = when {
        normalizedPreimage.isEmpty() || normalizedPreimage.endsWith("\n\n") -> ""
        normalizedPreimage.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    val append = when (
        val admission = strictUtf8(postimage.copyOfRange(preimage.size, postimage.size))
    ) {
        is StrictUtf8Admission.Admitted -> admission.text
        StrictUtf8Admission.Rejected -> return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.IMAGE_NOT_STRICT_UTF8,
        )
    }
    if (!append.startsWith(separator) || !append.endsWith('\n')) {
        return AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_APPEND_MISMATCH,
        )
    }
    val proposal = append.removePrefix(separator).dropLast(1)
    return if (
        proposal.isNotBlank() &&
        '\r' !in proposal &&
        '\uFEFF' !in proposal &&
        !proposal.endsWith('\n')
    ) {
        AddDeclarationProposalAdmission.Admitted(proposal)
    } else {
        AddDeclarationProposalAdmission.Rejected(
            MutationPostconditionAdmissionFailure.ADD_DECLARATION_PROPOSAL_INVALID,
        )
    }
}

private sealed interface StrictUtf8Admission {
    data class Admitted(
        val text: String,
    ) : StrictUtf8Admission

    data object Rejected : StrictUtf8Admission
}

/**
 * Proof transition: [ByteArray] -> [StrictUtf8Admission].
 *
 * Establishes strict UTF-8 text or a closed rejected state. Raw bytes are extracted only at exact
 * mutation image parsing boundaries.
 */
private fun strictUtf8(bytes: ByteArray): StrictUtf8Admission = try {
    StrictUtf8Admission.Admitted(
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString(),
    )
} catch (_: CharacterCodingException) {
    StrictUtf8Admission.Rejected
}
