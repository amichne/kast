package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
class CompilerFileBottomInsertion private constructor(
    @DocField(description = "Compiler-authorized UTF-16 insertion offset at the file bottom.")
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
    @DocField(description = "Normalized absolute path of the existing Kotlin file target.")
    val targetPath: AdditionTargetPath,
    @DocField(description = "Required SHA-256 of the exact target preimage bytes.")
    val targetPreimageSha256: AdditionTargetPreimageSha256,
    @DocField(description = "Imported source owner that admits the target path.")
    val owner: AdditionSourceOwner,
    @DocField(description = "Parsed Kotlin package shared by the target and proposed declaration.")
    val packageIdentity: AdditionKotlinPackage,
    @DocField(description = "Compiler-observed proposed top-level declaration.")
    val declaration: AdditionTopLevelDeclaration,
    @DocField(description = "Compiler-authorized insertion point in the target file.")
    val insertion: CompilerFileBottomInsertion,
    @DocField(description = "Closed newline rule used to create the exact postimage.")
    val newlinePolicy: AdditionNewlinePolicy,
    @DocField(description = "Exact semantic generation, project model, classpath, and source hashes.")
    val context: ExactAdditionProofContext,
    @DocField(description = "Complete compiler-backed declaration collision proof.")
    val collisionEvidence: ExactAdditionCollisionEvidence,
    @DocField(description = "Complete compiler-backed outbound reference proof.")
    val outboundEvidence: ExactAdditionOutboundEvidence,
    @DocField(description = "Complete compiler-backed current rebinding baseline.")
    val rebindingBaseline: ExactAdditionRebindingBaseline,
    @DocField(description = "SHA-256 of the exact target postimage bytes.")
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
