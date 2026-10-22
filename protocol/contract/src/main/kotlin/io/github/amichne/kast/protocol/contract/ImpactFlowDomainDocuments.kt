@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
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

/** Captured native allowance preserves finite output grants and the admitted execution mode. */
@Serializable
@JsonClassDiscriminator("mode")
sealed interface ImpactFlowBudgetDocument {
    val maxResults: ResultLimit
    val maxReturnedBytes: ReturnedByteLimit

    @Serializable
    @SerialName("BOUNDED")
    data class Bounded(
        @Serializable(with = ExecutionElapsedSerializer::class) val maxElapsedMillis: ElapsedTimeLimitMillis,
        @Serializable(with = ExecutionWorkSerializer::class) val maxWorkUnits: WorkUnitLimit,
        @Serializable(with = ExecutionResultsSerializer::class) override val maxResults: ResultLimit,
        @Serializable(with = ExecutionBytesSerializer::class) override val maxReturnedBytes: ReturnedByteLimit,
    ) : ImpactFlowBudgetDocument

    @Serializable
    @SerialName("RUNNER_CONTROLLED")
    data class RunnerControlled(
        @Serializable(with = ExecutionResultsSerializer::class) override val maxResults: ResultLimit,
        @Serializable(with = ExecutionBytesSerializer::class) override val maxReturnedBytes: ReturnedByteLimit,
    ) : ImpactFlowBudgetDocument

    fun permitsWork(work: Long): Boolean =
        when (this) {
            is Bounded -> work <= maxWorkUnits.value
            is RunnerControlled -> true
        }

    fun permitsElapsedMillis(elapsed: Long): Boolean =
        when (this) {
            is Bounded -> elapsed <= maxElapsedMillis.value
            is RunnerControlled -> true
        }

    companion object {
        operator fun invoke(
            maxElapsedMillis: ElapsedTimeLimitMillis,
            maxWorkUnits: WorkUnitLimit,
            maxResults: ResultLimit,
            maxReturnedBytes: ReturnedByteLimit,
        ): Bounded = Bounded(maxElapsedMillis, maxWorkUnits, maxResults, maxReturnedBytes)

        fun from(
            resources: ResourceBudget,
            pageResults: ResultLimit,
            returnedBytes: ReturnedByteLimit,
        ): ImpactFlowBudgetDocument =
            when (resources) {
                is ResourceBudget.Bounded ->
                    Bounded(
                        resources.elapsedTimeLimit,
                        resources.workUnitLimit,
                        resources.resultLimit.intersect(pageResults),
                        returnedBytes,
                    )
                ResourceBudget.RunnerControlled -> RunnerControlled(pageResults, returnedBytes)
            }
    }
}

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
