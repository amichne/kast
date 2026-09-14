package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.fingerprintFields
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

internal sealed interface IntellijSourceContinuationAdmission {
    data class Admitted(val cursor: IntellijSourceEntityCursor) : IntellijSourceContinuationAdmission

    data object Rejected : IntellijSourceContinuationAdmission
}

/** Project-owned bounded registry. Entries retain detached source identity and scope only. */
class IntellijSourceReadContinuations(private val limits: ReadLimits = ReadLimits.Default) {
    private enum class Lifetime {
        ACTIVE,
        RETIRED,
    }

    private var lifetime = Lifetime.ACTIVE
    private var sequence = 0L
    private val entries =
        object : LinkedHashMap<SourceReadContinuation, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceReadContinuation, Entry>?): Boolean =
                size > limits[ReadLimitParameter.SOURCE_CONTINUATIONS].value
        }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        entries.clear()
    }

    @Synchronized
    internal fun admit(context: SourceReadContext, request: SourceReadRequest): IntellijSourceContinuationAdmission {
        if (lifetime == Lifetime.RETIRED) return IntellijSourceContinuationAdmission.Rejected
        return when (val page = request.page) {
            SourceReadPage.First -> IntellijSourceContinuationAdmission.Admitted(IntellijSourceEntityCursor(0))
            is SourceReadPage.Continue -> {
                val entry = entries[page.continuation] ?: return IntellijSourceContinuationAdmission.Rejected
                if (entry.snapshot.context != context || entry.request != request.binding()) {
                    IntellijSourceContinuationAdmission.Rejected
                } else {
                    IntellijSourceContinuationAdmission.Admitted(
                        IntellijSourceEntityCursor(
                            entry.nextOrdinal,
                            entry.snapshot,
                            entry.regionFingerprint,
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    internal fun issue(
        request: SourceReadRequest,
        capture: IntellijSelectedSourceCapture,
        nextOrdinal: Int,
    ): Refinement<SourceReadContinuation, SourceReadRejection> {
        if (lifetime == Lifetime.RETIRED || sequence == Long.MAX_VALUE) {
            retire()
            return Refinement.Rejected(SourceReadRejection.SOURCE_UNAVAILABLE)
        }
        val binding = request.binding()
        val retained = Entry(binding, capture.snapshot, capture.regionSelector.fingerprint.value, nextOrdinal)
        entries.entries
            .firstOrNull { it.value == retained }
            ?.let {
                return Refinement.Refined(it.key)
            }
        sequence += 1
        val predecessor = (request.page as? SourceReadPage.Continue)?.continuation?.value.orEmpty()
        val canonical = buildString {
            appendBoundedField("intellij-source-entity-page-v1")
            appendBoundedField(sequence.toString())
            appendBoundedField(binding.toString())
            appendBoundedField(capture.snapshot.toString())
            appendBoundedField(capture.regionSelector.fingerprint.value)
            appendBoundedField(nextOrdinal.toString())
            appendBoundedField(predecessor)
        }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString(
                separator = ""
            ) { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val continuation =
            when (val parsed = SourceReadContinuation.parse("source-read-continuation-v1|$digest")) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            }
        entries[continuation] = retained
        return Refinement.Refined(continuation)
    }

    private data class Entry(
        val request: SourceRequestBinding,
        val snapshot: SourceSnapshot,
        val regionFingerprint: String,
        val nextOrdinal: Int,
    )
}

private data class SourceRequestBinding(
    val anchor: SourceAnchorBinding,
    val region: RegionSelection,
    val entities: EntitySelectionBinding,
    val text: TextProjection,
)

private sealed interface SourceAnchorBinding {
    data class Candidate(val canonical: String) : SourceAnchorBinding

    data class Symbol(val fingerprint: String) : SourceAnchorBinding

    data class Source(val fingerprint: String) : SourceAnchorBinding
}

private sealed interface EntitySelectionBinding {
    data object None : EntitySelectionBinding

    data class Matching(
        val containment: Containment,
        val filters: List<EntityFilterBinding>,
    ) : EntitySelectionBinding
}

private sealed interface EntityFilterBinding {
    data class Declarations(
        val kinds: List<io.github.amichne.kast.source.contract.DeclarationKind>,
        val visibility: List<DeclarationVisibility>?,
    ) : EntityFilterBinding

    data object Parameters : EntityFilterBinding

    data object Calls : EntityFilterBinding

    data object References : EntityFilterBinding
}

private fun SourceReadRequest.binding(): SourceRequestBinding =
    SourceRequestBinding(
        anchor =
            when (val value = anchor) {
                is SourceReadAnchor.Candidate -> SourceAnchorBinding.Candidate(value.selector.canonical())
                is SourceReadAnchor.Symbol -> SourceAnchorBinding.Symbol(value.selector.fingerprint.value)
                is SourceReadAnchor.Source -> SourceAnchorBinding.Source(value.selector.fingerprint.value)
            },
        region = region,
        entities = entities.binding(),
        text = text,
    )

private fun EntitySelection.binding(): EntitySelectionBinding =
    when (this) {
        EntitySelection.None -> EntitySelectionBinding.None
        is EntitySelection.Matching ->
            EntitySelectionBinding.Matching(
                containment,
                filters.map { filter ->
                    when (filter) {
                        is EntityFilter.Declarations ->
                            EntityFilterBinding.Declarations(
                                filter.kinds.values,
                                when (val visibility = filter.visibility) {
                                    VisibilitySelection.Any -> null
                                    is VisibilitySelection.Exact -> visibility.values
                                },
                            )
                        EntityFilter.Parameters -> EntityFilterBinding.Parameters
                        EntityFilter.Calls -> EntityFilterBinding.Calls
                        EntityFilter.References -> EntityFilterBinding.References
                    }
                },
            )
    }

private fun CandidateSelector.canonical(): String =
    when (this) {
        is CandidateSelector.Declaration ->
            buildString {
                appendBoundedField(lease.identity.revisionKey.value)
                appendBoundedField(lease.workspaceRoot.value)
                appendBoundedField("declaration")
                appendBoundedField(SymbolSearchScope.snapshot(selection.scope).toString())
                selection.constraints.fingerprintFields().forEach { appendBoundedField(it) }
                appendBoundedField(selection.candidate.toString())
            }
        is CandidateSelector.File ->
            buildString {
                appendBoundedField(lease.identity.revisionKey.value)
                appendBoundedField(lease.workspaceRoot.value)
                appendBoundedField("file")
                appendBoundedField(SymbolSearchScope.snapshot(scope).toString())
                constraints.fingerprintFields().forEach { appendBoundedField(it) }
                appendBoundedField(file.path.value)
            }
        is CandidateSelector.Range ->
            buildString {
                appendBoundedField(lease.identity.revisionKey.value)
                appendBoundedField(lease.workspaceRoot.value)
                appendBoundedField("range")
                appendBoundedField(SymbolSearchScope.snapshot(scope).toString())
                constraints.fingerprintFields().forEach { appendBoundedField(it) }
                appendBoundedField(file.path.value)
                appendBoundedField(startInclusive.value.toString())
                appendBoundedField(endExclusive.value.toString())
            }
    }

private fun StringBuilder.appendBoundedField(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}
