package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MutationPostconditionQuery(
    val authority: MutationPostconditionAuthority,
)

@Serializable
sealed interface MutationPostconditionAuthority {
    @Serializable
    @SerialName("RENAME")
    data class Rename(
        val proof: ExactRenameProof,
        val edits: List<TextEdit>,
        val images: List<ExactFileImage>,
    ) : MutationPostconditionAuthority

    @Serializable
    @SerialName("REPLACEMENT")
    data class Replacement(
        val proof: ExactReplacementProof,
        val edit: TextEdit,
        val images: List<ExactFileImage>,
    ) : MutationPostconditionAuthority

    @Serializable
    @SerialName("ADD_FILE")
    data class AddFile(
        val proof: ExactAddFileProof,
        val postimage: ExactByteImage,
    ) : MutationPostconditionAuthority

    @Serializable
    @SerialName("ADD_DECLARATION")
    data class AddDeclaration(
        val proof: ExactAddDeclarationProof,
        val image: ExactFileImage,
    ) : MutationPostconditionAuthority
}
