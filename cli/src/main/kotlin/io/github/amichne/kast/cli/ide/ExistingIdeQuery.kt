package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.Refinement

enum class ExistingIdeFailure {
    INVALID_NAME, HOST_UNAVAILABLE, DESCRIPTOR_REJECTED, RESPONSE_REJECTED,
    REQUEST_TOO_LARGE, DEADLINE_EXCEEDED, TRANSPORT_REJECTED, SCHEMA_UNAVAILABLE,
}

class ExistingIdeClassName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ExistingIdeClassName, ExistingIdeFailure> =
            if (raw.toByteArray(Charsets.UTF_8).size in 1..512 &&
                (raw.first().isLetter() || raw.first() == '_') && raw.all { it.isLetterOrDigit() || it == '_' }) {
                Refinement.Refined(ExistingIdeClassName(raw))
            } else Refinement.Rejected(ExistingIdeFailure.INVALID_NAME)
    }
}

sealed interface ExistingIdeOperation {
    data object Status : ExistingIdeOperation
    data class Classes(val name: ExistingIdeClassName) : ExistingIdeOperation
    data class Supertype(val name: ExistingIdeQualifiedClassName) : ExistingIdeOperation
}

class ExistingIdeQualifiedClassName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ExistingIdeQualifiedClassName, ExistingIdeFailure> =
            if (raw.toByteArray(Charsets.UTF_8).size in 1..4096 &&
                raw.split('.').all { ExistingIdeClassName.parse(it) is Refinement.Refined }) {
                Refinement.Refined(ExistingIdeQualifiedClassName(raw))
            } else Refinement.Rejected(ExistingIdeFailure.INVALID_NAME)
    }
}

/** The only runtime capability this path receives; it cannot demand an isolated worker. */
fun interface ExistingIdeClient {
    fun query(root: CanonicalRoot, operation: ExistingIdeOperation): ExistingIdeExchange
}

sealed interface ExistingIdeExchange {
    class Received internal constructor(val document: CliJsonDocument) : ExistingIdeExchange
    data class Rejected(val failure: ExistingIdeFailure) : ExistingIdeExchange
}
