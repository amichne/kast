package io.github.amichne.kast.kernel

private const val MAX_PERMANENT_ID_LENGTH = 96
private val PERMANENT_ID_FORMAT =
    Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)*")

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
