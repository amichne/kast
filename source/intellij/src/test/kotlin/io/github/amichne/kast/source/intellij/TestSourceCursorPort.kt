package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationPort
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadCursorRetentionFailure
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest

/** Case-owned detached storage effect; production proof owns binding and authority decisions. */
internal class TestSourceCursorPort : SourceReadContinuationPort {
    private sealed interface Admission {
        data object Open : Admission

        data class Refused(val reason: SourceReadRejection) : Admission
    }

    private var admission: Admission = Admission.Open
    private val entries = linkedMapOf<SourceReadContinuation, SourceReadCursorProof>()

    fun rejectAdmissions(reason: SourceReadRejection) {
        admission = Admission.Refused(reason)
    }

    override fun admit(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): Refinement<SourceReadEntityCursor, SourceReadRejection> =
        when (val admitted = admission) {
            is Admission.Refused -> Refinement.Rejected(admitted.reason)
            Admission.Open ->
                when (val page = request.page) {
                    SourceReadPage.First -> Refinement.Refined(SourceReadEntityCursor.First)
                    is SourceReadPage.Continue ->
                        entries[page.continuation]?.admit(context, request)
                            ?: Refinement.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE)
                }
        }

    override fun issue(
        proof: SourceReadCursorProof
    ): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure> {
        entries.entries
            .firstOrNull { it.value.samePosition(proof) }
            ?.let {
                return Refinement.Refined(it.key)
            }
        val token =
            when (
                val parsed =
                    SourceReadContinuation.parse(
                        "source-read-continuation-v1|" + (entries.size + 1).toString(16).padStart(64, '0')
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("Fixture token must satisfy its grammar")
            }
        entries[token] = proof
        return Refinement.Refined(token)
    }
}
