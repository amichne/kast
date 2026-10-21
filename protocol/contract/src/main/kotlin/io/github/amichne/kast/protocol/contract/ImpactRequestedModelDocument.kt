@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Public model sites select native work. Callers supply no retained value-state evidence. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRequestedModelDocument {
    val schemaVersion: ImpactModelFormatDocument
    val model: ImpactModelIdentityDocument

    fun reference(): ImpactModelDocument =
        when (this) {
            is Representation -> ImpactModelDocument.Representation(schemaVersion, model, states, rules)
            is Boundary -> ImpactModelDocument.Boundary(schemaVersion, model, rules.map { it.reference() })
        }

    @Serializable
    @SerialName("REPRESENTATION")
    data class Representation(
        override val schemaVersion: ImpactModelFormatDocument,
        override val model: ImpactModelIdentityDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 32, uniqueItems = true)
        val states: BoundedProtocolList<ImpactModelIdentifierDocument>,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val rules: BoundedProtocolList<ImpactRepresentationRuleDocument>,
    ) : ImpactRequestedModelDocument

    @Serializable
    @SerialName("BOUNDARY")
    data class Boundary(
        override val schemaVersion: ImpactModelFormatDocument,
        override val model: ImpactModelIdentityDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val rules: BoundedProtocolList<ImpactRequestedBoundaryRuleDocument>,
    ) : ImpactRequestedModelDocument
}

@Serializable
data class ImpactRequestedBoundaryPositionDocument(
    val site: ImpactRequestedValueSiteDocument,
    val kind: ImpactBoundaryKindDocument,
    val contract: ImpactBoundaryContractDocument,
    val slot: ImpactModelIdentifierDocument,
) {
    fun reference(): ImpactBoundaryPositionDocument =
        ImpactBoundaryPositionDocument(site.reference(), kind, contract, slot)
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRequestedBoundaryRuleDocument {
    val id: ImpactModelIdentifierDocument

    fun reference(): ImpactBoundaryRuleDocument =
        when (this) {
            is Continuation ->
                ImpactBoundaryRuleDocument.Continuation(id, source.reference(), target.reference(), assumptions)
            is Terminal -> ImpactBoundaryRuleDocument.Terminal(id, source.reference(), meaning)
        }

    @Serializable
    @SerialName("CONTINUATION")
    data class Continuation(
        override val id: ImpactModelIdentifierDocument,
        val source: ImpactRequestedBoundaryPositionDocument,
        val target: ImpactRequestedBoundaryPositionDocument,
        @ProtocolCollectionConstraint(maximumItems = 2, uniqueItems = true)
        val assumptions: BoundedProtocolList<ImpactBoundaryCompatibilityDocument>,
    ) : ImpactRequestedBoundaryRuleDocument

    @Serializable
    @SerialName("TERMINAL")
    data class Terminal(
        override val id: ImpactModelIdentifierDocument,
        val source: ImpactRequestedBoundaryPositionDocument,
        val meaning: ImpactBoundaryTerminalDocument,
    ) : ImpactRequestedBoundaryRuleDocument
}
