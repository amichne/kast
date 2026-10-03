package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.protocol.contract.ImpactModelPrimitiveFailure
import io.github.amichne.kast.protocol.contract.ProtocolCollectionFailure
import io.github.amichne.kast.protocol.contract.ProtocolOffsetFailure
import io.github.amichne.kast.protocol.contract.ProtocolTextFailure
import io.github.amichne.kast.protocol.contract.QueryDiscoveryMeasureFailure
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFailure

sealed interface ImpactPathProjectionFailure {
    data class Text(val cause: ProtocolTextFailure) : ImpactPathProjectionFailure

    data class Offset(val cause: ProtocolOffsetFailure) : ImpactPathProjectionFailure

    data class Model(val cause: ImpactModelPrimitiveFailure) : ImpactPathProjectionFailure

    data class Collection(val cause: ProtocolCollectionFailure) : ImpactPathProjectionFailure

    data class DomainFingerprint(val cause: QueryRelationDomainFailure) : ImpactPathProjectionFailure

    data class Count(val cause: QueryDiscoveryMeasureFailure) : ImpactPathProjectionFailure

    data class Budget(val cause: PositiveLimitFailure) : ImpactPathProjectionFailure

    data object DOMAIN_PROJECTION_REJECTED : ImpactPathProjectionFailure
}
