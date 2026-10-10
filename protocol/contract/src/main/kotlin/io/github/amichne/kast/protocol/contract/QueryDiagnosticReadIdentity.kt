package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import kotlinx.serialization.Serializable

private const val DIAGNOSTIC_READ_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

enum class QueryDiagnosticReadIdentityFailure {
    MALFORMED
}

/** Identifies the original diagnostic allocation; grants no semantic admission or freshness authority. */
@Serializable(with = QueryDiagnosticReadIdentitySerializer::class)
@JvmInline
value class QueryDiagnosticReadIdentity private constructor(val value: String) {
    companion object {
        private val syntax = Regex(DIAGNOSTIC_READ_PATTERN)

        fun fromBoundary(value: UUID): QueryDiagnosticReadIdentity = QueryDiagnosticReadIdentity(value.toString())

        fun parse(raw: String): Refinement<QueryDiagnosticReadIdentity, QueryDiagnosticReadIdentityFailure> =
            if (syntax.matches(raw)) Refinement.Refined(QueryDiagnosticReadIdentity(raw))
            else Refinement.Rejected(QueryDiagnosticReadIdentityFailure.MALFORMED)
    }
}

internal object QueryDiagnosticReadIdentitySerializer :
    RefiningStringSerializer<QueryDiagnosticReadIdentity>(
        serialName = "QueryDiagnosticReadIdentity",
        minimumLength = 36,
        maximumLength = 36,
        pattern = DIAGNOSTIC_READ_PATTERN,
    ) {
    override fun raw(value: QueryDiagnosticReadIdentity): String = value.value

    override fun refine(raw: String): Refinement<QueryDiagnosticReadIdentity, *> =
        QueryDiagnosticReadIdentity.parse(raw)
}
