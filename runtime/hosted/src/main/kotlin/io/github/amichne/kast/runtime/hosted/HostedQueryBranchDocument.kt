package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.query.contract.QueryBranchAccounting
import io.github.amichne.kast.query.contract.QueryBranchObservation
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Hash identities join the original read without logging paths, names, source, tokens or compiler objects. */
@Serializable
internal data class HostedQueryBranchDocument
private constructor(
    val readId: String?,
    val branch: String,
    val source: String,
    val authority: String,
    val effectiveExpansionDomain: String,
    val scope: String,
    val sourceKinds: String,
    val generatedSources: String,
    val reads: Int,
    val chargedWorkUnits: Long,
    val examinedWorkUnits: Long?,
    val elapsedMillis: Long,
    val emittedFacts: Int,
    val outcome: String,
    val limitations: List<String>,
) {
    companion object {
        fun from(trace: HostedReadTraceObservation, accounting: QueryBranchAccounting): HostedQueryBranchDocument {
            val scope = SymbolSearchScope.snapshot(accounting.effectiveScope)
            return HostedQueryBranchDocument(
                when (trace) {
                    HostedReadTraceObservation.Unobserved -> null
                    is HostedReadTraceObservation.Observed -> trace.identity.value.toString()
                },
                accounting.branch.name,
                accounting.source.fingerprint.value,
                accounting.source.lease.identity.revisionKey.value,
                accounting.domainFingerprint.value,
                scope.kind.name,
                scope.sourceKinds.name,
                scope.generatedSources.name,
                accounting.reads,
                accounting.chargedWorkUnits.value,
                when (val work = accounting.examinedWorkUnits) {
                    QueryWorkUsage.Unobserved -> null
                    is QueryWorkUsage.Observed -> work.count.value
                },
                accounting.elapsed.inWholeMilliseconds,
                accounting.emittedFacts.value,
                accounting.outcome.name,
                accounting.limitations.sortedBy { it.ordinal }.map { it.name },
            )
        }
    }
}

internal fun hostedQueryBranchObservation(
    trace: HostedReadTraceObservation,
    publish: (HostedQueryBranchDocument) -> Unit = ::logHostedQueryBranch,
): QueryBranchObservation = QueryBranchObservation { publish(HostedQueryBranchDocument.from(trace, it)) }

private val branchJson = Json { encodeDefaults = true }

private fun logHostedQueryBranch(document: HostedQueryBranchDocument) {
    Logger.getInstance(HostedEndpointService::class.java)
        .info("kast_query_branch " + branchJson.encodeToString(document))
}
