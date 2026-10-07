package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryRunResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Counts describe this returned page, including productive pages with no semantic rows. */
@Serializable
internal data class QueryPageProgressCliDocument(
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val rows: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("walk_evidence")
    val walkEvidence: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("reference_evidence")
    val referenceEvidence: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("relation_evidence")
    val relationEvidence: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("scope_exclusions")
    val scopeExclusions: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("callback_observations")
    val callbackObservations: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("callable_observations")
    val callableObservations: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("excluded_callbacks")
    val excludedCallbacks: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val omissions: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val failures: Int,
)

internal fun QueryRunResult.pageProgress(): QueryPageProgressCliDocument =
    QueryPageProgressCliDocument(
        rows = items.values.size,
        walkEvidence = walkObservations.values.size,
        referenceEvidence =
            referenceObservations.values.size + walkObservations.values.sumOf { it.referenceOccurrences.values.size },
        relationEvidence = relationObservations.values.size,
        scopeExclusions =
            relationObservations.values.sumOf { it.scopeExclusions.values.size } +
                walkObservations.values.sumOf { it.scopeExclusions.values.size },
        callbackObservations =
            relationObservations.values.sumOf { it.callbackObservations.values.size } +
                walkObservations.values.sumOf { it.callbackObservations.values.size },
        callableObservations =
            relationObservations.values.sumOf { it.callableObservations.values.size } +
                walkObservations.values.sumOf { it.callableObservations.values.size },
        excludedCallbacks =
            relationObservations.values.sumOf { observation ->
                observation.callbackObservations.values.count {
                    it.namedPolicy is io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument.Excluded
                }
            } +
                walkObservations.values.sumOf { observation ->
                    observation.callbackObservations.values.count {
                        it.observation.namedPolicy is
                            io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument.Excluded
                    }
                },
        omissions = omissions.values.size,
        failures = failures.values.size,
    )
