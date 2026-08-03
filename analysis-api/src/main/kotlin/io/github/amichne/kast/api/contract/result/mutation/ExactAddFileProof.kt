package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AddFileTargetState {
    ABSENT,
}

@Serializable
class ExactAddFileProof private constructor(
    val targetPath: AdditionTargetPath,
    val targetState: AddFileTargetState,
    val owner: AdditionSourceOwner,
    val packageIdentity: AdditionKotlinPackage,
    @SerialName("declarations")
    private val storedDeclarations: List<AdditionTopLevelDeclaration>,
    val context: ExactAdditionProofContext,
    val collisionEvidence: ExactAdditionCollisionEvidence,
    val outboundEvidence: ExactAdditionOutboundEvidence,
    val rebindingBaseline: ExactAdditionRebindingBaseline,
    val postimageSha256: AdditionPostimageSha256,
) {
    val declarations: List<AdditionTopLevelDeclaration>
        get() = Collections.unmodifiableList(storedDeclarations)

    init {
        require(targetState == AddFileTargetState.ABSENT) { "Add-file proof target state must be ABSENT" }
        if (storedDeclarations.isEmpty()) {
            throw AdditionProofIncompleteException.of(
                AdditionProofLimitation.ZERO_DECLARATIONS,
                message = "Add-file proof requires at least one compiler-proven top-level declaration",
            )
        }
        validateAdditionTargetOwner(targetPath, owner)
        validateAdditionDeclarations(packageIdentity, storedDeclarations)
        require(collisionEvidence.declarationCardinality.value == storedDeclarations.size) {
            "Add-file collision evidence cardinality must match its declaration count"
        }
        require(context.contextFileHashes.none { it.filePath == targetPath.value }) {
            "An absent add-file target must not have a context file hash"
        }
        validateZeroAdditionRebindingBaseline(rebindingBaseline)
        validateAdditionContextCoverage(context, outboundEvidence, rebindingBaseline)
    }

    override fun equals(other: Any?): Boolean = other is ExactAddFileProof &&
        targetPath == other.targetPath &&
        targetState == other.targetState &&
        owner == other.owner &&
        packageIdentity == other.packageIdentity &&
        storedDeclarations == other.storedDeclarations &&
        context == other.context &&
        collisionEvidence == other.collisionEvidence &&
        outboundEvidence == other.outboundEvidence &&
        rebindingBaseline == other.rebindingBaseline &&
        postimageSha256 == other.postimageSha256

    override fun hashCode(): Int = listOf(
        targetPath,
        targetState,
        owner,
        packageIdentity,
        storedDeclarations,
        context,
        collisionEvidence,
        outboundEvidence,
        rebindingBaseline,
        postimageSha256,
    ).hashCode()

    companion object {
        fun of(
            targetPath: AdditionTargetPath,
            owner: AdditionSourceOwner,
            packageIdentity: AdditionKotlinPackage,
            declarations: List<AdditionTopLevelDeclaration>,
            context: ExactAdditionProofContext,
            collisionEvidence: ExactAdditionCollisionEvidence,
            outboundEvidence: ExactAdditionOutboundEvidence,
            rebindingBaseline: ExactAdditionRebindingBaseline,
            postimageSha256: AdditionPostimageSha256,
        ): ExactAddFileProof = ExactAddFileProof(
            targetPath = targetPath,
            targetState = AddFileTargetState.ABSENT,
            owner = owner,
            packageIdentity = packageIdentity,
            storedDeclarations = declarations.toList(),
            context = context,
            collisionEvidence = collisionEvidence,
            outboundEvidence = outboundEvidence,
            rebindingBaseline = rebindingBaseline,
            postimageSha256 = postimageSha256,
        )
    }
}
