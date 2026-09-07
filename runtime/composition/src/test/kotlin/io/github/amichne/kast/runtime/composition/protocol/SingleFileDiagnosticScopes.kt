package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolver
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.kernel.Refinement

/** Explicit single-file fixture for existing diagnostic projection tests, not a runtime fallback. */
internal val singleFileDiagnosticScopes = DiagnosticScopeResolver { query ->
    when (val admitted = DiagnosticScope.fromCanonicalPaths(query.lease, listOf(query.path))) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
    }
}
