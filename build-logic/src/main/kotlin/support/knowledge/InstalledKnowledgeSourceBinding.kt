package support.knowledge

internal sealed interface InstalledKnowledgeModuleOwnership {
    data class Owned(val module: InstalledKnowledgeModuleInput) : InstalledKnowledgeModuleOwnership
    data class Rejected(val failure: InstalledKnowledgeOwnershipFailure) : InstalledKnowledgeModuleOwnership
}

internal enum class InstalledKnowledgeOwnershipFailure { NO_MODULE, AMBIGUOUS_MODULE }

internal fun installedKnowledgeOwningModule(
    sourcePath: String,
    modules: List<InstalledKnowledgeModuleInput>,
): InstalledKnowledgeModuleOwnership {
    val candidates = modules.filter { sourcePath.startsWith(it.moduleDirectory.trimEnd('/') + "/") }
    if (candidates.isEmpty()) return InstalledKnowledgeModuleOwnership.Rejected(InstalledKnowledgeOwnershipFailure.NO_MODULE)
    val longest = candidates.maxOf { it.moduleDirectory.length }
    val owners = candidates.filter { it.moduleDirectory.length == longest }
    return if (owners.size == 1) InstalledKnowledgeModuleOwnership.Owned(owners.single())
    else InstalledKnowledgeModuleOwnership.Rejected(InstalledKnowledgeOwnershipFailure.AMBIGUOUS_MODULE)
}

internal fun installedKnowledgeGoverningGuides(
    sourcePath: String,
    guides: List<InstalledKnowledgeGuideInput>,
): List<String> = guides.filter {
    it.scopeDirectory == "." || sourcePath.startsWith(it.scopeDirectory.trimEnd('/') + "/")
}.map { it.path }.sorted()
