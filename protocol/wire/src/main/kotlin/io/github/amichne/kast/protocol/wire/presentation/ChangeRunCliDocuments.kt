package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ChangeRunDocument

/** Verified application already carries the actual diff; rejected phases retain all recovery evidence. */
object ChangeRunCliDocuments {
    fun project(value: ChangeRunDocument): CanonicalJsonDocument = factory.create(value)

    private val factory =
        CanonicalJsonDocument.generated(ChangeRunDocument.serializer()) {
            when (it) {
                is ChangeRunDocument.Complete -> it.copy(plan = null)
                is ChangeRunDocument.Rejected -> it
            }
        }
}
