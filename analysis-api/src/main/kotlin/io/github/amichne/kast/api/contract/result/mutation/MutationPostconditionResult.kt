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
    val filePath: ExactFileImagePath,
    val sha256: ExactFileImageSha256,
)

@Serializable
sealed interface MutationPostconditionEvidence {
    @Serializable
    @SerialName("RENAME")
    data class Rename(
        val resultingTarget: SymbolIdentity,
        val evidence: RelationshipResultEvidence.Complete,
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
        val resultingTarget: SymbolIdentity,
        val sourceRange: Location,
        val signature: ReplacementDeclarationSignature,
        val outboundEvidence: ReplacementOutboundEvidence.Complete,
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
        val owner: AdditionSourceOwner,
        val packageIdentity: AdditionKotlinPackage,
        val declarations: List<AdditionTopLevelDeclaration>,
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
        val owner: AdditionSourceOwner,
        val packageIdentity: AdditionKotlinPackage,
        val declaration: AdditionTopLevelDeclaration,
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
    val status: MutationPostconditionStatus,
    val operation: MutationPostconditionOperation,
    val currentGeneration: MutationSemanticGeneration,
    val postimages: List<VerifiedMutationPostimage>,
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
