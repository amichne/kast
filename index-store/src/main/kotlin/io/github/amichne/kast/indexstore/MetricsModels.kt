package io.github.amichne.kast.indexstore

data class FanInMetric(
    val targetFqName: String,
    val targetPath: String?,
    val targetModuleName: String?,
    val occurrenceCount: Int,
    val sourceFileCount: Int,
    val sourceModuleCount: Int,
)

data class FanOutMetric(
    val sourcePath: String,
    val sourceModuleName: String?,
    val occurrenceCount: Int,
    val targetSymbolCount: Int,
    val targetFileCount: Int,
    val targetModuleCount: Int,
    val externalTargetCount: Int,
)

data class ModuleCouplingMetric(
    val sourceModuleName: String,
    val targetModuleName: String,
    val referenceCount: Int,
)

data class DeadCodeCandidate(
    val identifier: String,
    val path: String,
    val moduleName: String?,
    val packageName: String?,
    val confidence: MetricsConfidence,
    val reason: String,
)

data class ChangeImpactNode(
    val sourcePath: String,
    val depth: Int,
    val viaTargetFqName: String,
    val occurrenceCount: Int,
    val semantics: ImpactSemantics,
)

enum class MetricsConfidence {
    LOW,
}

enum class ImpactSemantics {
    FILE_LEVEL_APPROXIMATION,
}
