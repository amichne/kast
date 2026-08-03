package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MutationPostconditionStatus { VERIFIED }

@Serializable
enum class MutationPostconditionOperation { RENAME, REPLACEMENT, ADD_FILE, ADD_DECLARATION }

@Serializable
data class VerifiedMutationPostimage(
    @DocField(description = "Normalized absolute path of the verified postimage.")
    val filePath: ExactFileImagePath,
    @DocField(description = "SHA-256 of the exact verified postimage bytes.")
    val sha256: ExactFileImageSha256,
)

@Serializable
sealed interface MutationPostconditionEvidence {
    @Serializable
    @SerialName("RENAME")
    data class Rename(
        @DocField(description = "Compiler-resolved identity of the renamed declaration after mutation.")
        val resultingTarget: SymbolIdentity,
        @DocField(description = "Complete compiler-backed relationship evidence after rename.")
        @Serializable(with = RelationshipResultEvidence.CompleteSerializer::class)
        val evidence: RelationshipResultEvidence.Complete,
        @DocField(description = "Every exact compiler-resolved rename occurrence after mutation.")
        val occurrences: List<ExactRenameOccurrence>,
    ) : MutationPostconditionEvidence {
        init {
            require(evidence.cardinality.totalCount == occurrences.size) {
                "Verified rename cardinality must match its exact occurrences"
            }
            require(occurrences.all { occurrence -> occurrence.resolvedTarget == resultingTarget }) {
                "Every verified rename occurrence must resolve to the resulting target"
            }
        }
    }

    @Serializable
    @SerialName("REPLACEMENT")
    data class Replacement(
        @DocField(description = "Compiler-resolved identity of the replaced declaration after mutation.")
        val resultingTarget: SymbolIdentity,
        @DocField(description = "Exact full source range of the replaced declaration after mutation.")
        val sourceRange: Location,
        @DocField(description = "Compiler-observed declaration signature after mutation.")
        val signature: ReplacementDeclarationSignature,
        @DocField(description = "Complete compiler-backed outbound reference evidence after replacement.")
        @Serializable(with = ReplacementOutboundEvidence.CompleteSerializer::class)
        val outboundEvidence: ReplacementOutboundEvidence.Complete,
        @DocField(description = "Every exact compiler-resolved outbound reference after replacement.")
        val outboundReferences: List<ExactReplacementOutboundReference>,
    ) : MutationPostconditionEvidence {
        init {
            require(outboundEvidence.cardinality.totalCount == outboundReferences.size) {
                "Verified replacement cardinality must match its exact outbound occurrences"
            }
            require(
                (resultingTarget.kind == SymbolKind.FUNCTION && signature is ReplacementFunctionSignature) ||
                    (resultingTarget.kind == SymbolKind.PROPERTY && signature is ReplacementPropertySignature),
            ) { "Verified replacement signature kind must match its resulting target" }
        }
    }

    @Serializable
    @SerialName("ADD_FILE")
    data class AddFile(
        @DocField(description = "Imported source owner of the added file after mutation.")
        val owner: AdditionSourceOwner,
        @DocField(description = "Parsed Kotlin package of the added file after mutation.")
        val packageIdentity: AdditionKotlinPackage,
        @DocField(description = "Every compiler-observed top-level declaration in the added file.")
        val declarations: List<AdditionTopLevelDeclaration>,
        @DocField(description = "Complete compiler-backed outbound reference evidence after file addition.")
        val outboundEvidence: ExactAdditionOutboundEvidence,
    ) : MutationPostconditionEvidence {
        init {
            require(declarations.isNotEmpty()) { "Verified add-file evidence needs at least one declaration" }
            validateAdditionDeclarations(packageIdentity, declarations)
            require(outboundEvidence.cardinality.value == outboundEvidence.occurrences.size)
        }
    }

    @Serializable
    @SerialName("ADD_DECLARATION")
    data class AddDeclaration(
        @DocField(description = "Imported source owner of the target file after mutation.")
        val owner: AdditionSourceOwner,
        @DocField(description = "Parsed Kotlin package of the added declaration after mutation.")
        val packageIdentity: AdditionKotlinPackage,
        @DocField(description = "Compiler-observed top-level declaration after mutation.")
        val declaration: AdditionTopLevelDeclaration,
        @DocField(description = "Complete compiler-backed outbound reference evidence after declaration addition.")
        val outboundEvidence: ExactAdditionOutboundEvidence,
    ) : MutationPostconditionEvidence {
        init {
            validateAdditionDeclarations(packageIdentity, listOf(declaration))
            require(outboundEvidence.cardinality.value == outboundEvidence.occurrences.size)
        }
    }
}

@Serializable
class MutationPostconditionResult private constructor(
    @DocField(description = "Closed successful postcondition status.")
    val status: MutationPostconditionStatus,
    @DocField(description = "Mutation operation verified by this result.")
    val operation: MutationPostconditionOperation,
    @DocField(description = "Semantic source generation that verified the postcondition.")
    val currentGeneration: MutationSemanticGeneration,
    @DocField(description = "Exact verified hash for every mutation postimage.")
    val postimages: List<VerifiedMutationPostimage>,
    @DocField(description = "Operation-specific compiler evidence for the verified postcondition.")
    val evidence: MutationPostconditionEvidence,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(status == MutationPostconditionStatus.VERIFIED)
        require(postimages.isNotEmpty() && postimages.distinctBy { it.filePath }.size == postimages.size) {
            "Verified mutation postimages must contain one hash per path"
        }
        require(postimages == postimages.sortedBy { it.filePath.value }) {
            "Verified mutation postimages must use deterministic path order"
        }
        require(operation.matches(evidence)) { "Mutation operation must match its exact compiler evidence" }
    }

    companion object {
        fun verified(
            operation: MutationPostconditionOperation,
            currentGeneration: MutationSemanticGeneration,
            postimages: List<VerifiedMutationPostimage>,
            evidence: MutationPostconditionEvidence,
        ): MutationPostconditionResult = MutationPostconditionResult(
            status = MutationPostconditionStatus.VERIFIED,
            operation = operation,
            currentGeneration = currentGeneration,
            postimages = postimages.toList(),
            evidence = evidence,
        )
    }
}

private fun MutationPostconditionOperation.matches(evidence: MutationPostconditionEvidence): Boolean = when (this) {
    MutationPostconditionOperation.RENAME -> evidence is MutationPostconditionEvidence.Rename
    MutationPostconditionOperation.REPLACEMENT -> evidence is MutationPostconditionEvidence.Replacement
    MutationPostconditionOperation.ADD_FILE -> evidence is MutationPostconditionEvidence.AddFile
    MutationPostconditionOperation.ADD_DECLARATION -> evidence is MutationPostconditionEvidence.AddDeclaration
}
