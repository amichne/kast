package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement

private const val MAX_DIAGNOSTIC_CODE_LENGTH = 256
private const val MAX_DIAGNOSTIC_MESSAGE_LENGTH = 32_768

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}

enum class DiagnosticCodeFailure {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
}

@JvmInline
value class DiagnosticCode private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<DiagnosticCode, DiagnosticCodeFailure>`.
         *
         * Establishes a non-blank, bounded, single-line compiler diagnostic identity.
         * [DiagnosticCodeFailure] is the closed expected failure. Raw text may enter only from
         * the request-local compiler diagnostic projection boundary.
         */
        fun parse(raw: String): Refinement<DiagnosticCode, DiagnosticCodeFailure> = when {
            raw.isBlank() -> Refinement.Rejected(DiagnosticCodeFailure.BLANK)
            raw.length > MAX_DIAGNOSTIC_CODE_LENGTH ->
                Refinement.Rejected(DiagnosticCodeFailure.TOO_LONG)
            raw.any(Char::isISOControl) ->
                Refinement.Rejected(DiagnosticCodeFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(DiagnosticCode(raw))
        }
    }
}

enum class DiagnosticMessageFailure {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
}

@JvmInline
value class DiagnosticMessage private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<DiagnosticMessage, DiagnosticMessageFailure>`.
         *
         * Establishes a non-blank, bounded detached compiler message with no unsupported control
         * characters. [DiagnosticMessageFailure] is the closed expected failure. Raw text may
         * enter only from the request-local compiler diagnostic projection boundary.
         */
        fun parse(raw: String): Refinement<DiagnosticMessage, DiagnosticMessageFailure> = when {
            raw.isBlank() -> Refinement.Rejected(DiagnosticMessageFailure.BLANK)
            raw.length > MAX_DIAGNOSTIC_MESSAGE_LENGTH ->
                Refinement.Rejected(DiagnosticMessageFailure.TOO_LONG)
            raw.any { character ->
                character.isISOControl() && character != '\n' && character != '\t'
            } -> Refinement.Rejected(DiagnosticMessageFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(DiagnosticMessage(raw))
        }
    }
}

enum class DiagnosticTextRangeFailure {
    NEGATIVE_START,
    END_BEFORE_START,
}

@JvmInline
value class DiagnosticTextOffset internal constructor(
    val value: Int,
)

@ConsistentCopyVisibility
data class DiagnosticTextRange private constructor(
    val start: DiagnosticTextOffset,
    val endExclusive: DiagnosticTextOffset,
) {
    companion object {
        /**
         * Proof transition: `(Int, Int) ->
         * Refinement<DiagnosticTextRange, DiagnosticTextRangeFailure>`.
         *
         * Establishes a non-negative half-open range whose end is not before its start.
         * [DiagnosticTextRangeFailure] is the closed expected failure. Raw offsets may enter only
         * from the request-local PSI/K2 projection boundary.
         */
        fun fromBoundary(
            start: Int,
            endExclusive: Int,
        ): Refinement<DiagnosticTextRange, DiagnosticTextRangeFailure> = when {
            start < 0 -> Refinement.Rejected(DiagnosticTextRangeFailure.NEGATIVE_START)
            endExclusive < start ->
                Refinement.Rejected(DiagnosticTextRangeFailure.END_BEFORE_START)
            else -> Refinement.Refined(
                DiagnosticTextRange(
                    DiagnosticTextOffset(start),
                    DiagnosticTextOffset(endExclusive),
                ),
            )
        }
    }
}

data class DiagnosticLocation(
    val file: DiagnosticSourceFile,
    val range: DiagnosticTextRange,
)

enum class DiagnosticFactFailure {
    FILE_OUTSIDE_SCOPE,
    INVALID_RANGE,
    INVALID_CODE,
    INVALID_MESSAGE,
}

/** Detached compiler diagnostic permanently owned by one exact scope and generation. */
class DiagnosticFact private constructor(
    val scope: DiagnosticScope,
    val location: DiagnosticLocation,
    val severity: DiagnosticSeverity,
    val code: DiagnosticCode,
    val message: DiagnosticMessage,
) {
    val generation: EvidenceGeneration
        get() = scope.lease.generation

    companion object {
        /**
         * Proof transition: `(DiagnosticScope, DiagnosticSourceFile, Int, Int,
         * DiagnosticSeverity, String, String) ->
         * Refinement<DiagnosticFact, Set<DiagnosticFactFailure>>`.
         *
         * Establishes that the detached diagnostic belongs to a file in the exact scope and
         * inherits that scope's semantic generation, with typed range, code, and message.
         * [DiagnosticFactFailure] is the closed expected failure. Raw offsets and compiler text
         * may enter only at the request-local K2 projection boundary.
         */
        fun fromBoundary(
            scope: DiagnosticScope,
            file: DiagnosticSourceFile,
            start: Int,
            endExclusive: Int,
            severity: DiagnosticSeverity,
            code: String,
            message: String,
        ): Refinement<DiagnosticFact, Set<DiagnosticFactFailure>> {
            val failures = linkedSetOf<DiagnosticFactFailure>()
            if (file !in scope.files) {
                failures += DiagnosticFactFailure.FILE_OUTSIDE_SCOPE
            }
            val range = DiagnosticTextRange.fromBoundary(start, endExclusive)
            if (range is Refinement.Rejected) {
                failures += DiagnosticFactFailure.INVALID_RANGE
            }
            val parsedCode = DiagnosticCode.parse(code)
            if (parsedCode is Refinement.Rejected) {
                failures += DiagnosticFactFailure.INVALID_CODE
            }
            val parsedMessage = DiagnosticMessage.parse(message)
            if (parsedMessage is Refinement.Rejected) {
                failures += DiagnosticFactFailure.INVALID_MESSAGE
            }
            if (failures.isNotEmpty()) {
                return Refinement.Rejected(failures)
            }
            return when (range) {
                is Refinement.Rejected ->
                    Refinement.Rejected(setOf(DiagnosticFactFailure.INVALID_RANGE))
                is Refinement.Refined -> when (parsedCode) {
                    is Refinement.Rejected ->
                        Refinement.Rejected(setOf(DiagnosticFactFailure.INVALID_CODE))
                    is Refinement.Refined -> when (parsedMessage) {
                        is Refinement.Rejected -> Refinement.Rejected(
                            setOf(DiagnosticFactFailure.INVALID_MESSAGE),
                        )
                        is Refinement.Refined -> Refinement.Refined(
                            DiagnosticFact(
                                scope,
                                DiagnosticLocation(file, range.value),
                                severity,
                                parsedCode.value,
                                parsedMessage.value,
                            ),
                        )
                    }
                }
            }
        }
    }
}
