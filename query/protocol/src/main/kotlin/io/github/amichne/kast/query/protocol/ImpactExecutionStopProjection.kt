package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactExecutionStopDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop

internal fun QueryImpactExecutionStop.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactExecutionStopDocument> =
    when (this) {
        is QueryImpactExecutionStop.Cycle ->
            producer
                .impactDocument(references)
                .impactZip(prefix.impactEach { it.impactDocument(references) })
                .impactZip(count(repeatedAt.toLong()))
                .impactZip(source.impactDocument(references))
                .impactMap { (route, source) ->
                    ImpactExecutionStopDocument.Cycle(route.first.first, route.first.second, route.second, source)
                }
        is QueryImpactExecutionStop.CheckpointCapacity ->
            source
                .impactDocument(references)
                .impactZip(count(required.value))
                .impactZip(count(available.value))
                .impactMap { (required, available) ->
                    ImpactExecutionStopDocument.CheckpointCapacity(required.first, required.second, available)
                }
    }

private fun count(value: Long): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(value).impactFailure(ImpactPathProjectionFailure::Count)
