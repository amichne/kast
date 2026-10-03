@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class ImpactModelIdentityDocument(
    val id: ImpactModelIdentifierDocument,
    val version: ImpactModelVersionDocument,
    val provenance: ImpactModelIdentifierDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactSemanticBasisDocument {
    val root: ProtocolText

    @Serializable
    @SerialName("PUBLISHED")
    data class Published(override val root: ProtocolText, val generation: ImpactEvidenceRevisionDocument) :
        ImpactSemanticBasisDocument

    @Serializable
    @SerialName("LIVE")
    data class Live(
        override val root: ProtocolText,
        @ProtocolStringConstraint(pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val host: ProtocolText,
        val epoch: ImpactEvidenceRevisionDocument,
        val contentView: ImpactContentViewDocument,
        val referenceVersion: ImpactModelFormatDocument,
    ) : ImpactSemanticBasisDocument
}

@Serializable
enum class ImpactContentViewDocument {
    SAVED_PSI_COMMITTED
}

@Serializable data class ImpactSourceRangeDocument(val start: ProtocolOffset, val end: ProtocolOffset)

@Serializable
data class ImpactDeclarationReferenceDocument(
    val basis: ImpactSemanticBasisDocument,
    val file: ProtocolText,
    val range: ImpactSourceRangeDocument,
    @ProtocolStringConstraint(
        pattern = "^canonical-signature-sha256-v1\\|[0-9a-f]{64}$",
        minimumLength = 94,
        maximumLength = 94,
    )
    val compilerIdentity: ProtocolText,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactModelValuePositionDocument {
    @Serializable @SerialName("RESULT") data object Result : ImpactModelValuePositionDocument

    @Serializable
    @SerialName("ARGUMENT")
    data class Argument(val index: ProtocolOffset) : ImpactModelValuePositionDocument
}

@Serializable
data class ImpactCallablePositionDocument(
    val declaration: ImpactDeclarationReferenceDocument,
    val position: ImpactModelValuePositionDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRepresentationRuleDocument {
    val id: ImpactModelIdentifierDocument

    @Serializable
    @SerialName("ORIGIN")
    data class Origin(
        override val id: ImpactModelIdentifierDocument,
        val output: ImpactCallablePositionDocument,
        val state: ImpactModelIdentifierDocument,
    ) : ImpactRepresentationRuleDocument

    @Serializable
    @SerialName("TRANSFER")
    data class Transfer(
        override val id: ImpactModelIdentifierDocument,
        val input: ImpactCallablePositionDocument,
        val output: ImpactCallablePositionDocument,
    ) : ImpactRepresentationRuleDocument

    @Serializable
    @SerialName("TRANSFORMATION")
    data class Transformation(
        override val id: ImpactModelIdentifierDocument,
        val input: ImpactCallablePositionDocument,
        val output: ImpactCallablePositionDocument,
        val from: ImpactModelIdentifierDocument,
        val to: ImpactModelIdentifierDocument,
    ) : ImpactRepresentationRuleDocument

    @Serializable
    @SerialName("CONSUMER_EXPECTATION")
    data class ConsumerExpectation(
        override val id: ImpactModelIdentifierDocument,
        val input: ImpactCallablePositionDocument,
        val state: ImpactModelIdentifierDocument,
    ) : ImpactRepresentationRuleDocument
}

/** Syntax is not model authority. Ingress must validate the whole vocabulary before domain binding. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactModelDocument {
    val schemaVersion: ImpactModelFormatDocument
    val model: ImpactModelIdentityDocument

    @Serializable
    @SerialName("REPRESENTATION")
    data class Representation(
        override val schemaVersion: ImpactModelFormatDocument,
        override val model: ImpactModelIdentityDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 32, uniqueItems = true)
        val states: BoundedProtocolList<ImpactModelIdentifierDocument>,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val rules: BoundedProtocolList<ImpactRepresentationRuleDocument>,
    ) : ImpactModelDocument

    @Serializable
    @SerialName("BOUNDARY")
    data class Boundary(
        override val schemaVersion: ImpactModelFormatDocument,
        override val model: ImpactModelIdentityDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val rules: BoundedProtocolList<ImpactBoundaryRuleDocument>,
    ) : ImpactModelDocument
}
