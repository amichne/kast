package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticSeverityCounts(
    @DocField(description = "Exact number of error diagnostics across every page.")
    val error: Int,
    @DocField(description = "Exact number of warning diagnostics across every page.")
    val warning: Int,
    @DocField(description = "Exact number of informational diagnostics across every page.")
    val info: Int,
    @DocField(description = "Exact total diagnostics across every page.")
    val total: Int,
) {
    init {
        require(error >= 0 && warning >= 0 && info >= 0) { "Diagnostic severity counts must be non-negative" }
        require(total == error + warning + info) { "Diagnostic total must equal its severity counts" }
    }

    companion object {
        fun from(diagnostics: List<Diagnostic>): DiagnosticSeverityCounts =
            DiagnosticSeverityCounts(
                error = diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
                warning = diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                info = diagnostics.count { it.severity == DiagnosticSeverity.INFO },
                total = diagnostics.size,
            )
    }
}
