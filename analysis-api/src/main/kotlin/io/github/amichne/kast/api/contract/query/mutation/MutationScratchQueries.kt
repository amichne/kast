@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class MutationScratchInspectQuery(
    @DocField(description = "Required canonical UUID-v4 attempt admitted by this inspection.")
    val mutationAttemptId: String,
    @DocField(description = "Nonempty sorted unique canonical workspace-relative parent paths to inspect.")
    val workspaceRelativeParentPaths: List<String>,
    @DocField(description = "Journal-owned scratch sets sorted uniquely by targetFilePath.")
    val ownedScratchSets: List<MutationScratchSet>,
)

@Serializable
enum class MutationScratchRecoveryAction {
    RESTORE_PREIMAGE,
    FINALIZE_POSTIMAGE,
}

@Serializable
enum class MutationScratchDirection {
    FORWARD,
    RESTORE_PREIMAGE,
}

@Serializable
@JsonClassDiscriminator("state")
sealed interface MutationScratchRecoveryPreimage {
    @Serializable
    @SerialName("ABSENT")
    data object Absent : MutationScratchRecoveryPreimage

    @Serializable
    @SerialName("PRESENT")
    data class Present(val image: ExactByteImage) : MutationScratchRecoveryPreimage
}

@Serializable
data class MutationScratchRecoveryQuery(
    @DocField(description = "Required active canonical UUID-v4 mutation attempt.")
    val mutationAttemptId: String,
    @DocField(description = "Closed recovery action applied only to supplied journal authority.")
    val action: MutationScratchRecoveryAction,
    @DocField(description = "Closed direction that defines the exact image authorized for each scratch role.")
    val scratchDirection: MutationScratchDirection,
    @DocField(description = "Normalized absolute recovery target path.")
    val targetFilePath: String,
    @DocField(description = "Exact target preimage, including explicit absence.")
    val preimage: MutationScratchRecoveryPreimage,
    @DocField(description = "Exact target postimage.")
    val postimage: ExactByteImage,
    @DocField(description = "Exact journal-supplied scratch authority for this transition.")
    val scratch: MutationScratchSet,
)
