package io.github.amichne.kast.cli.knowledge

internal fun admitModuleInventory(
    descriptors: List<KnowledgeModuleDescriptor>
): KnowledgeAdmission<Map<KnowledgeResourcePath, KnowledgeModuleDescriptor>> {
    val modules = linkedMapOf<KnowledgeResourcePath, KnowledgeModuleDescriptor>()
    for (module in descriptors) {
        if (
            !KNOWLEDGE_PROJECT.matches(module.projectPath) ||
                module.resource != knowledgeModuleResource(module.projectPath)
        ) {
            return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
        }
        when (val path = KnowledgeResourcePath.parse(module.resource)) {
            is KnowledgeAdmission.Accepted -> modules[path.value] = module
            is KnowledgeAdmission.Rejected -> return path
        }
    }
    return KnowledgeAdmission.Accepted(modules)
}

internal fun admitGuideInventory(
    references: List<KnowledgeGuideReference>
): KnowledgeAdmission<Map<KnowledgeResourcePath, KnowledgeGuideReference>> {
    val guides = linkedMapOf<KnowledgeResourcePath, KnowledgeGuideReference>()
    for (guide in references) {
        if (!validGuideReference(guide)) return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.GUIDE_REJECTED)
        when (val path = KnowledgeResourcePath.parse(guide.resource)) {
            is KnowledgeAdmission.Accepted -> {
                if (guides.put(path.value, guide) != null) {
                    return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.GUIDE_REJECTED)
                }
            }
            is KnowledgeAdmission.Rejected -> return path
        }
    }
    if (guides.values.none { it.path == "AGENTS.md" }) {
        return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.GUIDE_REJECTED)
    }
    return KnowledgeAdmission.Accepted(guides)
}

internal fun validManifestIdentity(document: KnowledgeManifestDocument): Boolean =
    document.productVersion.isNotBlank() && Regex("[0-9a-f]{40}").matches(document.sourceRevision)

internal fun validManifestInventory(document: KnowledgeManifestDocument): Boolean =
    document.modules.size in 1..MAX_KNOWLEDGE_MODULES &&
        document.guides.size in 1..MAX_KNOWLEDGE_GUIDES &&
        uniqueManifestInventory(document)

private fun uniqueManifestInventory(document: KnowledgeManifestDocument): Boolean =
    document.modules.distinctBy { it.projectPath }.size == document.modules.size &&
        document.guides.distinctBy { it.path }.size == document.guides.size

internal fun completeKnowledgeLimitations(limitations: List<KnowledgeLimitation>): Boolean =
    limitations.distinct().size == limitations.size && limitations.containsAll(KnowledgeLimitation.entries)

internal fun matchesModuleOwner(
    document: KnowledgeModuleDocument,
    descriptor: KnowledgeModuleDescriptor,
    directory: String,
): Boolean = document.projectPath == descriptor.projectPath && document.moduleDirectory == directory

internal fun validDeclarationInventory(document: KnowledgeModuleDocument): Boolean =
    document.declarations.size <= MAX_KNOWLEDGE_DECLARATIONS &&
        document.declarations.distinctBy { it.id }.size == document.declarations.size

internal fun matchesModuleGuidance(
    document: KnowledgeModuleDocument,
    manifest: AdmittedKnowledgeManifest,
    expectedGuides: List<String>,
): Boolean =
    document.governingGuides.map { it.resource }.sorted() == expectedGuides &&
        document.governingGuides.all { it in manifest.guides.values }

internal fun validDeclarationDescriptor(declaration: KnowledgeDeclarationDescriptor): Boolean =
    KNOWLEDGE_DIGEST.matches(declaration.id) &&
        validDeclarationName(declaration) &&
        validDeclarationPresentation(declaration)

private fun validDeclarationName(declaration: KnowledgeDeclarationDescriptor): Boolean =
    declaration.name.isNotBlank() && declaration.declarationPath.isNotBlank()

private fun validDeclarationPresentation(declaration: KnowledgeDeclarationDescriptor): Boolean =
    declaration.kind in KNOWLEDGE_KINDS && declaration.summary.length <= MAX_KNOWLEDGE_SUMMARY_CHARACTERS

internal fun matchesDeclarationIdentity(
    document: KnowledgeDeclarationDocument,
    descriptor: KnowledgeDeclarationDescriptor,
): Boolean =
    document.id == descriptor.id &&
        document.id == knowledgeDeclarationId(document) &&
        matchesDeclarationName(document, descriptor)

private fun matchesDeclarationName(
    document: KnowledgeDeclarationDocument,
    descriptor: KnowledgeDeclarationDescriptor,
): Boolean =
    document.declarationPath == descriptor.declarationPath &&
        document.kind == descriptor.kind &&
        document.name == descriptor.name

internal fun matchesDeclarationSource(
    document: KnowledgeDeclarationDocument,
    module: AdmittedKnowledgeModule,
): Boolean =
    document.projectPath == module.document.projectPath &&
        document.sourcePath.startsWith(module.document.moduleDirectory + "/") &&
        validKotlinSourcePath(document.sourcePath)

private fun validKotlinSourcePath(path: String): Boolean = validKnowledgeSourcePath(path) && path.endsWith(".kt")

internal fun matchesDeclarationDocumentation(
    document: KnowledgeDeclarationDocument,
    descriptor: KnowledgeDeclarationDescriptor,
    manifest: AdmittedKnowledgeManifest,
): Boolean =
    document.signature.isNotBlank() &&
        knowledgeSummary(document.documentation) == descriptor.summary &&
        document.governingGuides.sorted() == manifest.guidesFor(document.sourcePath)

private fun validGuideReference(guide: KnowledgeGuideReference): Boolean =
    validGuideSourcePath(guide.path) &&
        guide.resource == knowledgeGuideResource(guide.path) &&
        validGuideHash(guide.sha256)

private fun validGuideSourcePath(path: String): Boolean =
    (path == "AGENTS.md" || path.endsWith("/AGENTS.md")) && validKnowledgeSourcePath(path)

private fun validGuideHash(hash: String): Boolean =
    hash.startsWith("sha256:") && KNOWLEDGE_DIGEST.matches(hash.removePrefix("sha256:"))
