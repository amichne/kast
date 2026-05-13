package io.github.amichne.kast.shared.analysis

import org.jetbrains.kotlin.psi.KtImportDirective

data class ImportAnalysisResult(
    val usedImports: List<KtImportDirective>,
    val unusedImports: List<KtImportDirective>,
    val missingImports: List<String>,
)
