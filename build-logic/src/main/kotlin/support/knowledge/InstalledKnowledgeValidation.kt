package support.knowledge

internal const val INSTALLED_KNOWLEDGE_MAX_RESOURCE_BYTES = 8 * 1024 * 1024
private const val MAX_MODULES = 512
private const val MAX_GUIDES = 4096
private const val MAX_SOURCE_PATH_CHARACTERS = 4096
private val RESOURCE_COMPONENT = Regex("[A-Za-z0-9_.-]+")
private const val MAX_DECLARATIONS_PER_MODULE = 20_000
private val PROJECT_PATH = Regex(":[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")

internal fun validateInstalledKnowledge(input: InstalledKnowledgeInput): List<InstalledKnowledgeProjectionFailure> {
    val failures = mutableListOf<InstalledKnowledgeProjectionFailure>()
    if (input.modules.size !in 1..MAX_MODULES || input.guides.size !in 1..MAX_GUIDES) {
        failures += InstalledKnowledgeProjectionFailure.InventoryTooLargeOrEmpty
    }
    if (input.guides.none { it.path == "AGENTS.md" }) {
        failures += InstalledKnowledgeProjectionFailure.MissingRootGuide
    }
    input.guides.forEach { guide ->
        val expectedScope = guide.path.removeSuffix("AGENTS.md").trimEnd('/').ifEmpty { "." }
        if (!validGuidePath(guide.path) || guide.scopeDirectory != expectedScope) {
            failures += InstalledKnowledgeProjectionFailure.InvalidGuide(guide.path)
        }
    }
    input.guides.map { installedKnowledgeGuideResource(it.path) }.groupingBy { it }.eachCount()
        .filterValues { it > 1 }.keys.forEach { failures += InstalledKnowledgeProjectionFailure.DuplicateResource(it) }
    input.modules.forEach { module ->
        if (!PROJECT_PATH.matches(module.projectPath) || module.moduleDirectory != module.projectPath.removePrefix(":").replace(':', '/')) {
            failures += InstalledKnowledgeProjectionFailure.InvalidModule(module.projectPath)
        }
        if (module.governingGuidePaths.sorted() != installedKnowledgeGoverningGuides(module.moduleDirectory + "/", input.guides)) {
            failures += InstalledKnowledgeProjectionFailure.IncorrectGuidance(module.projectPath)
        }
        if (input.declarations.count { it.projectPath == module.projectPath } > MAX_DECLARATIONS_PER_MODULE) {
            failures += InstalledKnowledgeProjectionFailure.InventoryTooLargeOrEmpty
        }
    }
    input.declarations.forEach { declaration ->
        val ownership = installedKnowledgeOwningModule(declaration.sourcePath, input.modules)
        if (
            ownership !is InstalledKnowledgeModuleOwnership.Owned || ownership.module.projectPath != declaration.projectPath ||
            !validSourcePath(declaration.sourcePath) || !declaration.sourcePath.endsWith(".kt") ||
            declaration.name.isBlank() || declaration.declarationPath.isBlank() || declaration.signature.isBlank()
        ) failures += InstalledKnowledgeProjectionFailure.InvalidDeclaration(declaration.sourcePath)
        if (declaration.governingGuidePaths.sorted() != installedKnowledgeGoverningGuides(declaration.sourcePath, input.guides)) {
            failures += InstalledKnowledgeProjectionFailure.IncorrectGuidance(declaration.sourcePath)
        }
    }
    return failures.distinct()
}

internal fun installedKnowledgeGuideResource(path: String): String =
    "guides/${path.removeSuffix("AGENTS.md").trimEnd('/').ifEmpty { "root" }}.json"

private fun validGuidePath(path: String): Boolean =
    (path == "AGENTS.md" || path.endsWith("/AGENTS.md")) && validSourcePath(path) &&
        path.split('/').all { RESOURCE_COMPONENT.matches(it) }

private fun validSourcePath(path: String): Boolean =
    path.length <= MAX_SOURCE_PATH_CHARACTERS && !path.contains('\\') && path.split('/').all { part ->
        part.isNotEmpty() && part != "." && part != ".." && part.none(Char::isISOControl)
    }
