package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

/**
 * Proof transition: `SymbolSearchScope -> SymbolSearchScope`.
 *
 * A relation subject is admitted only from authored source. A destination scope may include generated source, so the
 * same declaration reused as a subject keeps every other scope property and excludes generated roots. A subject in a
 * generated root then fails its scope admission instead of being read under a broader scope.
 */
fun SymbolSearchScope.subjectScope(): SymbolSearchScope =
    when (this) {
        is SymbolSearchScope.ExactFile -> copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE)
        is SymbolSearchScope.Module -> copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE)
        is SymbolSearchScope.SourceSet -> copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE)
        is SymbolSearchScope.GradleProject -> copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE)
        is SymbolSearchScope.Workspace -> copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE)
    }
