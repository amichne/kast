package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.ExactRevalidationLocator
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection

/** A token indexes detached issuance facts only. It cannot restore strict read authority. */
fun interface ExactRevalidationReferences {
    fun locate(token: ProtocolText): Refinement<ExactRevalidationLocator, ExactRevalidationRejection>

    data object Unavailable : ExactRevalidationReferences {
        override fun locate(token: ProtocolText): Refinement<ExactRevalidationLocator, ExactRevalidationRejection> =
            Refinement.Rejected(ExactRevalidationRejection.UNRETAINED)
    }
}
