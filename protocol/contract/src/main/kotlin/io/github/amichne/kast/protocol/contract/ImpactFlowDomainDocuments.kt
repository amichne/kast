@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** The exact native question supporting a domain-end terminal; no absence claim extends beyond this domain. */
@Serializable
data class ImpactFlowDomainDocument(
    val subject: ImpactDeclarationReferenceDocument,
    val meaning: RelationKindDocument,
    val requestedDomain: QueryRelationRequestedDomainDocument,
    val domain: QueryRelationDomainDocument,
    val fingerprint: QueryRelationDomainFingerprint,
    val budget: ImpactFlowBudgetDocument,
    val position: ImpactFlowReadPositionDocument,
)

/** Captured native allowance has every bound; request defaults cannot erase an observed domain. */
@Serializable
data class ImpactFlowBudgetDocument(
    @Serializable(with = ExecutionElapsedSerializer::class) val maxElapsedMillis: ElapsedTimeLimitMillis,
    @Serializable(with = ExecutionWorkSerializer::class) val maxWorkUnits: WorkUnitLimit,
    @Serializable(with = ExecutionResultsSerializer::class) val maxResults: ResultLimit,
    @Serializable(with = ExecutionBytesSerializer::class) val maxReturnedBytes: ReturnedByteLimit,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFlowReadPositionDocument {
    @Serializable @SerialName("START") data object Start : ImpactFlowReadPositionDocument

    @Serializable
    @SerialName("RESUME")
    data class Resume(
        val fingerprint: QueryRelationDomainFingerprint,
        val provider: RelationProviderDocument,
        val position: QueryDiscoveryCountDocument,
        val consumedPrefix: QueryRelationDomainFingerprint,
        val limitations: BoundedProtocolList<RelationLimitationDocument>,
    ) : ImpactFlowReadPositionDocument
}

@Serializable
data class ImpactFlowEndObservationDocument(
    val source: ImpactValueSiteReferenceDocument,
    val domain: ImpactFlowDomainDocument,
    val examinedWorkUnits: QueryDiscoveryCountDocument,
    val retainedBytes: QueryDiscoveryCountDocument,
    val terminal: ImpactFlowTerminalDocument,
)

@Serializable
enum class ImpactFlowTerminalDocument {
    SUPPORTED_DOMAIN_EXHAUSTED
}
