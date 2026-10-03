// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("WORKSPACE") internal data object PublicToolImpactWorkspaceDomain : PublicToolExpansionScope

@Serializable
@SerialName("RETAINED_SEED")
internal data object PublicToolImpactRetainedDomain : PublicToolExpansionScope

@Serializable
@SerialName("SOURCE_DOMAIN")
internal data class PublicToolImpactSourceDomain(
    val sourceSets: BoundedProtocolList<ProtocolText>,
    val directory: ProtocolText? = null,
    val includeSubdirectories: Boolean? = null,
    val sourcePolicy: PublicToolSourcePolicy,
    val generatedSources: PublicToolGeneratedSources,
) : PublicToolExpansionScope

@Serializable
@SerialName("IMPACT")
internal data class PublicToolImpactSource(
    val seeds: BoundedProtocolList<io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument>,
    val declarations: BoundedProtocolList<io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument>,
    val models: BoundedProtocolList<io.github.amichne.kast.protocol.contract.ImpactModelDocument>,
    val domain: PublicToolExpansionScope,
    val flow: io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument,
) : PublicToolSource

@Serializable
@SerialName("IMPACT_WITNESS")
internal data class PublicToolImpactWitnessOutput(
    val section: io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
) : PublicToolReadResultOutput
