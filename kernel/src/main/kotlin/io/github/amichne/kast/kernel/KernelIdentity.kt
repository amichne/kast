package io.github.amichne.kast.kernel

private const val MAX_PERMANENT_ID_LENGTH = 96
private const val MAX_NAMED_ROOT_LENGTH = 128
private val PERMANENT_ID_FORMAT =
    Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)*")

enum class NamedRootFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

/**
 * A stable logical name for one repository root.
 *
 * This value does not claim filesystem admission or canonical-path proof. A workspace owner may
 * associate it with an admitted canonical path without exposing that platform representation to
 * the host-neutral kernel.
 */
@JvmInline
value class NamedRoot private constructor(
    val value: String,
) : Comparable<NamedRoot> {
    companion object {
        /**
         * Proof transition: `String -> Refinement<NamedRoot, NamedRootFailure>`.
         *
         * Establishes an exact, non-blank, bounded lowercase dot-separated logical root name.
         * [NamedRootFailure] is the closed expected failure. Raw text may be extracted only at
         * the workspace-admission or external protocol boundary.
         */
        fun parse(raw: String): Refinement<NamedRoot, NamedRootFailure> = when {
            raw.isBlank() -> Refinement.Rejected(NamedRootFailure.BLANK)
            raw.length > MAX_NAMED_ROOT_LENGTH -> Refinement.Rejected(NamedRootFailure.TOO_LONG)
            !PERMANENT_ID_FORMAT.matches(raw) -> Refinement.Rejected(NamedRootFailure.INVALID_FORMAT)
            else -> Refinement.Refined(NamedRoot(raw))
        }
    }

    override fun compareTo(other: NamedRoot): Int = value.compareTo(other.value)
}

enum class PermanentIdentityFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

@JvmInline
value class OperationId private constructor(
    val value: String,
) : Comparable<OperationId> {
    companion object {
        /**
         * Proof transition: `String -> Refinement<OperationId, PermanentIdentityFailure>`.
         *
         * Establishes an exact, non-blank, bounded lowercase dot-separated permanent operation
         * identity. [PermanentIdentityFailure] is the closed expected failure. Raw text may be
         * extracted only at the operation-registry or transport boundary.
         */
        fun parse(raw: String): Refinement<OperationId, PermanentIdentityFailure> = when {
            raw.isBlank() -> Refinement.Rejected(PermanentIdentityFailure.BLANK)
            raw.length > MAX_PERMANENT_ID_LENGTH -> Refinement.Rejected(PermanentIdentityFailure.TOO_LONG)
            !PERMANENT_ID_FORMAT.matches(raw) -> Refinement.Rejected(PermanentIdentityFailure.INVALID_FORMAT)
            else -> Refinement.Refined(OperationId(raw))
        }
    }

    override fun compareTo(other: OperationId): Int = value.compareTo(other.value)
}

@JvmInline
value class CapabilityId private constructor(
    val value: String,
) : Comparable<CapabilityId> {
    companion object {
        /**
         * Proof transition: `String -> Refinement<CapabilityId, PermanentIdentityFailure>`.
         *
         * Establishes an exact, non-blank, bounded lowercase dot-separated permanent capability
         * identity. [PermanentIdentityFailure] is the closed expected failure. Raw text may be
         * extracted only at the operation-registry or capability-advertisement boundary.
         */
        fun parse(raw: String): Refinement<CapabilityId, PermanentIdentityFailure> = when {
            raw.isBlank() -> Refinement.Rejected(PermanentIdentityFailure.BLANK)
            raw.length > MAX_PERMANENT_ID_LENGTH -> Refinement.Rejected(PermanentIdentityFailure.TOO_LONG)
            !PERMANENT_ID_FORMAT.matches(raw) -> Refinement.Rejected(PermanentIdentityFailure.INVALID_FORMAT)
            else -> Refinement.Refined(CapabilityId(raw))
        }
    }

    override fun compareTo(other: CapabilityId): Int = value.compareTo(other.value)
}

/**
 * Domain-owned static marker for one capability family.
 *
 * Effect owners define narrower capability values that implement this interface and restrict their
 * own construction. A marker exposes only its refined permanent identity across module boundaries;
 * it carries no effect implementation, service lookup, or recoverable platform authority.
 */
interface CapabilityMarker {
    val id: CapabilityId
}
