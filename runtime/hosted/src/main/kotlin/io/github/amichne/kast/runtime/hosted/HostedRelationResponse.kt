package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal typealias HostedRelationOutcome = OperationOutcome<RelationReadResult, RelationReadQualification, RelationReadRejection>

/** Response fitting retains a detached suffix before publishing a prefix. */
internal fun encodeHostedRelationResponse(
    semantic: HostedRelationOutcome,
    limits: ReadLimits,
    retain: (HostedRelationOutcome) -> HostedOutputRetention,
): HostedResponse = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.relationRead, semantic, limits)
