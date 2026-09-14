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
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.fingerprintFields
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal sealed interface IntellijSourceContinuationAdmission {
    data class Admitted(val cursor: IntellijSourceEntityCursor) : IntellijSourceContinuationAdmission

    data class Rejected(val reason: IntellijSourceContinuationRejection) : IntellijSourceContinuationAdmission
}

internal enum class IntellijSourceContinuationRejection {
    UNAVAILABLE,
    CONTEXT_MISMATCH,
    REQUEST_MISMATCH,
}

/** Project-owned bounded registry. Entries retain detached source identity and scope only. */
class IntellijSourceReadContinuations(
    private val limits: ReadLimits = ReadLimits.Default,
    private val clock: () -> Long = System::nanoTime,
) {
    private enum class Lifetime {
        ACTIVE,
        RETIRED,
    }

    private var lifetime = Lifetime.ACTIVE
    private var sequence = 0L
    private val entries = LinkedHashMap<SourceReadContinuation, Retained>(16, 0.75f, true)

    private data class Retained(val proof: Entry, val createdAt: Long, val chargedBytes: Long)

    private fun expire() {
        val now = clock()
        val ttl =
            TimeUnit.MILLISECONDS.toNanos(limits[ReadLimitParameter.SOURCE_CONTINUATION_TTL_MILLIS].value.toLong())
        entries.entries.removeIf { now - it.value.createdAt >= ttl }
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        entries.clear()
    }

    @Synchronized
    internal fun admit(context: SourceReadContext, request: SourceReadRequest): IntellijSourceContinuationAdmission {
        expire()
        if (lifetime == Lifetime.RETIRED)
            return IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.UNAVAILABLE)
        return when (val page = request.page) {
            SourceReadPage.First -> IntellijSourceContinuationAdmission.Admitted(IntellijSourceEntityCursor(0))
            is SourceReadPage.Continue -> {
                val entry =
                    entries[page.continuation]?.proof
                        ?: return IntellijSourceContinuationAdmission.Rejected(
                            IntellijSourceContinuationRejection.UNAVAILABLE
                        )
                if (entry.snapshot.context != context) {
                    IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.CONTEXT_MISMATCH)
                } else if (entry.request != request.binding()) {
                    IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.REQUEST_MISMATCH)
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
        expire()
        val binding = request.binding()
        val retained = Entry(binding, capture.snapshot, capture.regionSelector.fingerprint.value, nextOrdinal)
        entries.entries
            .firstOrNull { it.value.proof == retained }
            ?.let {
                return Refinement.Refined(it.key)
            }
        val bytes = retained.chargedBytes()
        val maximumBytes = limits[ReadLimitParameter.SOURCE_CONTINUATION_BYTES].value.toLong()
        if (bytes > maximumBytes) return Refinement.Rejected(SourceReadRejection.SOURCE_UNAVAILABLE)
        while (
            entries.size >= limits[ReadLimitParameter.SOURCE_CONTINUATIONS].value ||
                entries.values.sumOf { it.chargedBytes } + bytes > maximumBytes
        ) {
            entries.remove(entries.keys.first())
        }
        return when (val issued = token(request, capture, binding, nextOrdinal)) {
            is Refinement.Rejected -> issued
            is Refinement.Refined -> {
                entries[issued.value] = Retained(retained, clock(), bytes)
                issued
            }
        }
    }

    private fun token(
        request: SourceReadRequest,
        capture: IntellijSelectedSourceCapture,
        binding: SourceRequestBinding,
        nextOrdinal: Int,
    ): Refinement<SourceReadContinuation, SourceReadRejection> {
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
        return Refinement.Refined(continuation)
    }

    private data class Entry(
        val request: SourceRequestBinding,
        val snapshot: SourceSnapshot,
        val regionFingerprint: String,
        val nextOrdinal: Int,
    ) {
        /**
         * Conservative retention charge for detached identity text and its object/container overhead; not heap
         * measurement.
         */
        fun chargedBytes(): Long {
            val scopeFields =
                when (val scope = snapshot.readScope) {
                    SourceReadScope.ExactFile -> emptyList()
                    is SourceReadScope.Constrained ->
                        listOf(SymbolSearchScope.snapshot(scope.scope).toString()) +
                            scope.constraints.fingerprintFields()
                }
            val fields = listOf(request.toString(), snapshot.toString(), regionFingerprint) + scopeFields
            return fields.sumOf {
                it.toByteArray(StandardCharsets.UTF_8).size.toLong() * RETAINED_TEXT_FACTOR + RETAINED_FIELD_BYTES
            } + RETAINED_ENTRY_BYTES
        }
    }
}

private const val RETAINED_TEXT_FACTOR = 4L
private const val RETAINED_FIELD_BYTES = 128L
private const val RETAINED_ENTRY_BYTES = 4_096L

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
