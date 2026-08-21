package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.charset.StandardCharsets
import java.nio.file.Path

private const val MAX_DISCOVERY_CANDIDATE_NAME_LENGTH = 512
private const val MAX_DETACHED_VIRTUAL_FILE_URL_LENGTH = 4096
private val DETACHED_VIRTUAL_FILE_URL_FORMAT = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://.+")

enum class SymbolDiscoveryCandidateFailure {
    BLANK_NAME,
    NAME_TOO_LONG,
    NAME_CONTROL_CHARACTER,
    INVALID_FILE_LOCATION,
    FILE_LOCATION_TOO_LONG,
    INVALID_DECLARATION_OFFSET,
    FILE_CANDIDATE_HAS_DECLARATION_OFFSET,
    DECLARATION_CANDIDATE_MISSING_OFFSET,
    TEXT_CANDIDATE_MISSING_RANGE,
    INVALID_TEXT_RANGE,
}

@JvmInline
value class SymbolDiscoveryCandidateName private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * String to Refinement<SymbolDiscoveryCandidateName, SymbolDiscoveryCandidateFailure>.
         *
         * Establishes a non-blank, bounded detached display name without control characters.
         * [SymbolDiscoveryCandidateFailure] is the closed expected failure. Raw names may be
         * extracted only at native projection or transport boundaries.
         */
        fun parse(raw: String): Refinement<SymbolDiscoveryCandidateName, SymbolDiscoveryCandidateFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(SymbolDiscoveryCandidateFailure.BLANK_NAME)
                raw.length > MAX_DISCOVERY_CANDIDATE_NAME_LENGTH ->
                    Refinement.Rejected(SymbolDiscoveryCandidateFailure.NAME_TOO_LONG)
                raw.any(Char::isISOControl) ->
                    Refinement.Rejected(SymbolDiscoveryCandidateFailure.NAME_CONTROL_CHARACTER)
                else -> Refinement.Refined(SymbolDiscoveryCandidateName(raw))
            }
    }
}

@JvmInline
value class DetachedVirtualFileUrl private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * String to Refinement<DetachedVirtualFileUrl, SymbolDiscoveryCandidateFailure>.
         *
         * Establishes a non-blank, bounded, scheme-qualified detached virtual-file location.
         * [SymbolDiscoveryCandidateFailure] is the closed expected failure. Raw URLs may be
         * extracted only at native projection or transport boundaries.
         */
        fun parse(raw: String): Refinement<DetachedVirtualFileUrl, SymbolDiscoveryCandidateFailure> =
            when {
                raw.length > MAX_DETACHED_VIRTUAL_FILE_URL_LENGTH ->
                    Refinement.Rejected(SymbolDiscoveryCandidateFailure.FILE_LOCATION_TOO_LONG)
                !DETACHED_VIRTUAL_FILE_URL_FORMAT.matches(raw) ->
                    Refinement.Rejected(SymbolDiscoveryCandidateFailure.INVALID_FILE_LOCATION)
                else -> Refinement.Refined(DetachedVirtualFileUrl(raw))
            }
    }
}

@JvmInline
value class SymbolDiscoverySourceOffset private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * Int to Refinement<SymbolDiscoverySourceOffset, SymbolDiscoveryCandidateFailure>.
         *
         * Establishes a non-negative source offset for a generation-bound discovery candidate.
         * [SymbolDiscoveryCandidateFailure] is the closed expected failure. Raw offsets may be
         * extracted only at native projection, exact-selector resolution, or transport boundaries.
         */
        fun parse(raw: Int): Refinement<SymbolDiscoverySourceOffset, SymbolDiscoveryCandidateFailure> =
            if (raw >= 0) {
                Refinement.Refined(SymbolDiscoverySourceOffset(raw))
            } else {
                Refinement.Rejected(SymbolDiscoveryCandidateFailure.INVALID_DECLARATION_OFFSET)
            }
    }
}

sealed interface SymbolDiscoveryFileIdentity {
    val stableValue: String

    data class Workspace(
        val path: CanonicalWorkspaceFilePath,
    ) : SymbolDiscoveryFileIdentity {
        override val stableValue: String = path.value
    }

    data class External(
        val url: DetachedVirtualFileUrl,
    ) : SymbolDiscoveryFileIdentity {
        override val stableValue: String = url.value
    }

    companion object {
        /**
         * Proof transition:
         * CanonicalWorkspaceRoot + Path? + String to
         * Refinement<SymbolDiscoveryFileIdentity, SymbolDiscoveryCandidateFailure>.
         *
         * Establishes either an exact canonical in-workspace path or a bounded detached external
         * virtual-file URL. [SymbolDiscoveryCandidateFailure] is the closed expected failure.
         * Raw paths and URLs may be extracted only at the native projection and transport
         * boundaries.
         */
        fun fromBoundary(
            workspaceRoot: CanonicalWorkspaceRoot,
            nativePath: Path?,
            virtualFileUrl: String,
        ): Refinement<SymbolDiscoveryFileIdentity, SymbolDiscoveryCandidateFailure> {
            if (nativePath != null) {
                when (val workspacePath =
                    CanonicalWorkspaceFilePath.fromCanonicalPath(workspaceRoot, nativePath)
                ) {
                    is Refinement.Refined -> return Refinement.Refined(Workspace(workspacePath.value))
                    is Refinement.Rejected -> {
                        if (
                            workspacePath.failure !=
                            CanonicalWorkspaceFilePathFailure.FILE_OUTSIDE_WORKSPACE
                        ) {
                            return Refinement.Rejected(
                                SymbolDiscoveryCandidateFailure.INVALID_FILE_LOCATION,
                            )
                        }
                    }
                }
            }
            return when (val url = DetachedVirtualFileUrl.parse(virtualFileUrl)) {
                is Refinement.Refined -> Refinement.Refined(External(url.value))
                is Refinement.Rejected -> url
            }
        }
    }
}

@ConsistentCopyVisibility
data class SymbolDiscoverySourceRange private constructor(
    val startInclusive: SymbolDiscoverySourceOffset,
    val endExclusive: SymbolDiscoverySourceOffset,
) {
    companion object {
        /**
         * Proof transition: `(Int, Int) -> Refinement<SymbolDiscoverySourceRange,
         * SymbolDiscoveryCandidateFailure>`.
         *
         * Establishes one non-empty half-open text match range. The closed expected failure is
         * [SymbolDiscoveryCandidateFailure]. Raw offsets may be extracted only by indexed text
         * projection or transport boundaries.
         */
        fun parse(
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<SymbolDiscoverySourceRange, SymbolDiscoveryCandidateFailure> {
            val start = when (val parsed = SymbolDiscoverySourceOffset.parse(rawStartInclusive)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val end = when (val parsed = SymbolDiscoverySourceOffset.parse(rawEndExclusive)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            return if (end.value <= start.value) {
                Refinement.Rejected(SymbolDiscoveryCandidateFailure.INVALID_TEXT_RANGE)
            } else {
                Refinement.Refined(SymbolDiscoverySourceRange(start, end))
            }
        }
    }
}

sealed interface SymbolDiscoveryCandidateLocation {
    val file: SymbolDiscoveryFileIdentity

    data class File(
        override val file: SymbolDiscoveryFileIdentity,
    ) : SymbolDiscoveryCandidateLocation

    data class Declaration(
        override val file: SymbolDiscoveryFileIdentity,
        val offset: SymbolDiscoverySourceOffset,
    ) : SymbolDiscoveryCandidateLocation

    data class Text(
        override val file: SymbolDiscoveryFileIdentity,
        val range: SymbolDiscoverySourceRange,
    ) : SymbolDiscoveryCandidateLocation
}

@ConsistentCopyVisibility
data class SymbolDiscoveryCandidate private constructor(
    val lease: SemanticReadLease,
    val kind: SymbolDiscoveryKind,
    val name: SymbolDiscoveryCandidateName,
    val location: SymbolDiscoveryCandidateLocation,
) : Comparable<SymbolDiscoveryCandidate> {
    override fun compareTo(other: SymbolDiscoveryCandidate): Int =
        DISCOVERY_CANDIDATE_ORDER.compare(this, other)

    /**
     * Proof transition: SymbolDiscoveryCandidate to SymbolDiscoveryByteCount.
     *
     * Establishes the exact non-negative UTF-8 size of this candidate's canonical detached
     * projection. Raw bytes may be extracted only by bounded collectors and transport encoders.
     */
    fun projectedUtf8Size(): SymbolDiscoveryByteCount =
        when (
            val size = SymbolDiscoveryByteCount.parse(
                canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong(),
            )
        ) {
            is Refinement.Refined -> size.value
            is Refinement.Rejected -> error("UTF-8 byte size cannot be negative")
        }

    private fun canonicalProjection(): String = buildString {
        append(lease.workspaceRoot.value)
        append('\u0000')
        append(lease.generation.value)
        append('\u0000')
        append(kind.name)
        append('\u0000')
        append(name.value)
        append('\u0000')
        append(location.file.stableValue)
        when (val candidateLocation = location) {
            is SymbolDiscoveryCandidateLocation.File -> Unit
            is SymbolDiscoveryCandidateLocation.Declaration -> {
                append('\u0000')
                append(candidateLocation.offset.value)
            }
            is SymbolDiscoveryCandidateLocation.Text -> {
                append('\u0000')
                append(candidateLocation.range.startInclusive.value)
                append('\u0000')
                append(candidateLocation.range.endExclusive.value)
            }
        }
    }

    companion object {
        /**
         * Proof transition:
         * SymbolDiscoveryKind + String + SemanticReadLease + Path? + String + Int? to
         * Refinement<SymbolDiscoveryCandidate, SymbolDiscoveryCandidateFailure>.
         *
         * Establishes a generation-bound detached candidate with a bounded name, exact file
         * identity, and a non-negative declaration offset exactly when class or symbol discovery
         * requires one.
         * [SymbolDiscoveryCandidateFailure] is the closed expected failure. Raw IntelliJ values
         * may be extracted only by the request-local native projection adapter.
         */
        fun fromBoundary(
            kind: SymbolDiscoveryKind,
            rawName: String,
            lease: SemanticReadLease,
            nativePath: Path?,
            virtualFileUrl: String,
            rawOffset: Int?,
            rawEndOffset: Int? = null,
        ): Refinement<SymbolDiscoveryCandidate, SymbolDiscoveryCandidateFailure> {
            val name = when (val parsed = SymbolDiscoveryCandidateName.parse(rawName)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val file = when (
                val parsed =
                    SymbolDiscoveryFileIdentity.fromBoundary(
                        lease.workspaceRoot,
                        nativePath,
                        virtualFileUrl,
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val location = when (kind) {
                SymbolDiscoveryKind.FILE -> {
                    if (rawOffset != null) {
                        return Refinement.Rejected(
                            SymbolDiscoveryCandidateFailure.FILE_CANDIDATE_HAS_DECLARATION_OFFSET,
                        )
                    }
                    SymbolDiscoveryCandidateLocation.File(file)
                }
                SymbolDiscoveryKind.CLASS,
                SymbolDiscoveryKind.SYMBOL,
                    -> {
                    val offset = rawOffset ?: return Refinement.Rejected(
                        SymbolDiscoveryCandidateFailure.DECLARATION_CANDIDATE_MISSING_OFFSET,
                    )
                    when (val parsed = SymbolDiscoverySourceOffset.parse(offset)) {
                        is Refinement.Refined ->
                            SymbolDiscoveryCandidateLocation.Declaration(file, parsed.value)
                        is Refinement.Rejected -> return parsed
                    }
                }
                SymbolDiscoveryKind.TEXT -> {
                    val start = rawOffset ?: return Refinement.Rejected(
                        SymbolDiscoveryCandidateFailure.TEXT_CANDIDATE_MISSING_RANGE,
                    )
                    val end = rawEndOffset ?: return Refinement.Rejected(
                        SymbolDiscoveryCandidateFailure.TEXT_CANDIDATE_MISSING_RANGE,
                    )
                    when (val parsed = SymbolDiscoverySourceRange.parse(start, end)) {
                        is Refinement.Refined ->
                            SymbolDiscoveryCandidateLocation.Text(file, parsed.value)
                        is Refinement.Rejected -> return parsed
                    }
                }
            }
            return Refinement.Refined(SymbolDiscoveryCandidate(lease, kind, name, location))
        }

        private val DISCOVERY_CANDIDATE_ORDER =
            compareBy<SymbolDiscoveryCandidate>(
                { it.lease.workspaceRoot.value },
                { it.lease.generation.value },
                { it.kind.ordinal },
                { it.name.value },
                { it.location.file.stableValue },
                {
                    when (val location = it.location) {
                        is SymbolDiscoveryCandidateLocation.File -> -1
                        is SymbolDiscoveryCandidateLocation.Declaration -> location.offset.value
                        is SymbolDiscoveryCandidateLocation.Text ->
                            location.range.startInclusive.value
                    }
                },
            )
    }
}
