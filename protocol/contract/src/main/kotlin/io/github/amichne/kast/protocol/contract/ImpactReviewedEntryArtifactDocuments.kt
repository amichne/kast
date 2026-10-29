package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** JVM hash access does not add a serialized subtype or an implementation claim to metadata. */
interface ImpactReviewedJvmArtifactHashes {
    val binaryArtifactSha256: ProtocolText
    val sourceArtifactSha256: ProtocolText
    val sourceEntrySha256: ProtocolText
    val facadeSha256: ProtocolText
    val implementationSha256: ProtocolText
}

@Serializable
@SerialName("commonMain/kotlin/Collections.kt")
data class ImpactReviewedEntryDeclarationArtifactDocument(
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val binaryArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceEntrySha256: ProtocolText,
    val declarationLines: ImpactReviewedSourceLinesDocument,
    val metadata: ImpactReviewedMetadataEvidenceDocument,
) : ImpactReviewedArtifactsDocument

@Serializable
data class ImpactReviewedMetadataEvidenceDocument(
    val entry: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sha256: ProtocolText,
)
