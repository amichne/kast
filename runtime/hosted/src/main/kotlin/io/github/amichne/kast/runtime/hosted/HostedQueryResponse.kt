package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal fun encodeHostedQueryResponse(
    semantic: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection>,
    limits: ReadLimits = ReadLimits.Default,
): HostedResponse = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, semantic, limits)
