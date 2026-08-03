package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MutationPostconditionQuery(
    @DocField(description = "Persisted proof and exact file images authorized for postcondition verification.")
    val authority: MutationPostconditionAuthority,
)

@Serializable
sealed interface MutationPostconditionAuthority {
    @Serializable
    @SerialName("RENAME")
    data class Rename(
        @DocField(description = "Exact compiler-backed rename proof admitted before mutation.")
        val proof: ExactRenameProof,
        @DocField(description = "Exact rename edits that produced the candidate postimages.")
        val edits: List<TextEdit>,
        @DocField(description = "Exact preimage and postimage bytes for every renamed file.")
        val images: List<ExactFileImage>,
    ) : MutationPostconditionAuthority

    @Serializable
    @SerialName("REPLACEMENT")
    data class Replacement(
        @DocField(description = "Exact compiler-backed replacement proof admitted before mutation.")
        val proof: ExactReplacementProof,
        @DocField(description = "Exact declaration replacement edit.")
        val edit: TextEdit,
        @DocField(description = "Exact preimage and postimage bytes for the replaced file.")
        val images: List<ExactFileImage>,
    ) : MutationPostconditionAuthority

    @Serializable
    @SerialName("ADD_FILE")
    data class AddFile(
        @DocField(description = "Exact compiler-backed add-file proof admitted before mutation.")
        val proof: ExactAddFileProof,
        @DocField(description = "Exact bytes written to the new file.")
        val postimage: ExactByteImage,
    ) : MutationPostconditionAuthority

    @Serializable
    @SerialName("ADD_DECLARATION")
    data class AddDeclaration(
        @DocField(description = "Exact compiler-backed add-declaration proof admitted before mutation.")
        val proof: ExactAddDeclarationProof,
        @DocField(description = "Exact target file preimage and postimage bytes.")
        val image: ExactFileImage,
    ) : MutationPostconditionAuthority
}
