package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.NonNegativeInt
import kotlinx.serialization.Serializable

@Serializable
class CompilerFileBottomInsertion private constructor(
    val offset: NonNegativeInt,
) {
    override fun equals(other: Any?): Boolean = other is CompilerFileBottomInsertion && offset == other.offset

    override fun hashCode(): Int = offset.hashCode()

    companion object {
        fun at(offset: Int): CompilerFileBottomInsertion = CompilerFileBottomInsertion(NonNegativeInt(offset))
    }
}

@Serializable
enum class AdditionNewlinePolicy {
    PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
}

@Serializable
class ExactAddDeclarationProof private constructor(
    val targetPath: AdditionTargetPath,
    val targetPreimageSha256: AdditionTargetPreimageSha256,
    val owner: AdditionSourceOwner,
    val packageIdentity: AdditionKotlinPackage,
    val declaration: AdditionTopLevelDeclaration,
    val insertion: CompilerFileBottomInsertion,
    val newlinePolicy: AdditionNewlinePolicy,
    val context: ExactAdditionProofContext,
    val collisionEvidence: ExactAdditionCollisionEvidence,
    val outboundEvidence: ExactAdditionOutboundEvidence,
    val rebindingBaseline: ExactAdditionRebindingBaseline,
    val postimageSha256: AdditionPostimageSha256,
) {
    init {
        validateAdditionTargetOwner(targetPath, owner)
        validateAdditionDeclarations(packageIdentity, listOf(declaration))
        require(collisionEvidence.declarationCardinality.value == 1) {
            "Add-declaration collision evidence must prove exactly one declaration"
        }
        val targetContext = context.contextFileHashes.singleOrNull { it.filePath == targetPath.value }
        require(targetContext?.sha256 == targetPreimageSha256.value) {
            "Add-declaration target context hash must equal its exact target preimage SHA-256"
        }
        validateZeroAdditionRebindingBaseline(rebindingBaseline)
        validateAdditionContextCoverage(context, outboundEvidence, rebindingBaseline)
    }

    override fun equals(other: Any?): Boolean = other is ExactAddDeclarationProof &&
        targetPath == other.targetPath &&
        targetPreimageSha256 == other.targetPreimageSha256 &&
        owner == other.owner &&
        packageIdentity == other.packageIdentity &&
        declaration == other.declaration &&
        insertion == other.insertion &&
        newlinePolicy == other.newlinePolicy &&
        context == other.context &&
        collisionEvidence == other.collisionEvidence &&
        outboundEvidence == other.outboundEvidence &&
        rebindingBaseline == other.rebindingBaseline &&
        postimageSha256 == other.postimageSha256

    override fun hashCode(): Int = listOf(
        targetPath,
        targetPreimageSha256,
        owner,
        packageIdentity,
        declaration,
        insertion,
        newlinePolicy,
        context,
        collisionEvidence,
        outboundEvidence,
        rebindingBaseline,
        postimageSha256,
    ).hashCode()

    companion object {
        fun of(
            targetPath: AdditionTargetPath,
            targetPreimageSha256: AdditionTargetPreimageSha256,
            owner: AdditionSourceOwner,
            packageIdentity: AdditionKotlinPackage,
            declaration: AdditionTopLevelDeclaration,
            insertion: CompilerFileBottomInsertion,
            newlinePolicy: AdditionNewlinePolicy,
            context: ExactAdditionProofContext,
            collisionEvidence: ExactAdditionCollisionEvidence,
            outboundEvidence: ExactAdditionOutboundEvidence,
            rebindingBaseline: ExactAdditionRebindingBaseline,
            postimageSha256: AdditionPostimageSha256,
        ): ExactAddDeclarationProof = ExactAddDeclarationProof(
            targetPath = targetPath,
            targetPreimageSha256 = targetPreimageSha256,
            owner = owner,
            packageIdentity = packageIdentity,
            declaration = declaration,
            insertion = insertion,
            newlinePolicy = newlinePolicy,
            context = context,
            collisionEvidence = collisionEvidence,
            outboundEvidence = outboundEvidence,
            rebindingBaseline = rebindingBaseline,
            postimageSha256 = postimageSha256,
        )
    }
}
