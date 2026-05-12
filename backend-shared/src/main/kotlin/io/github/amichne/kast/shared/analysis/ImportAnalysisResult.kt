package io.github.amichne.kast.shared.analysis

data class ImportAnalysisResult(
    val usedImports: List<KtImportDirective>,
    val unusedImports: List<KtImportDirective>,
    val missingImports: List<String>,
)
