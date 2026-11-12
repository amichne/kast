package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedReadFailure
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection

internal fun ValueFlowRejection.Native.nativeImpactFailure(position: Int): Refinement.Rejected<QueryRunRejection> =
    Refinement.Rejected(
        QueryRunRejection.ImpactSourceRejected(
            QueryImpactSourceFailureDocument.NativeRead(nativeDocument(), queryPosition(position))
        )
    )

internal fun ValueProducerSeedReadFailure.seedFailure(position: Int): Refinement.Rejected<QueryRunRejection> =
    when (this) {
        is ValueProducerSeedReadFailure.Native -> cause.nativeImpactFailure(position)
        is ValueProducerSeedRejection -> impactFailure(impactFailure(), position)
    }
