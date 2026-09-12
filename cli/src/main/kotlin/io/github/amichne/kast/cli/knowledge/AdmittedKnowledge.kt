package io.github.amichne.kast.cli.knowledge

/** Retains the admitted version, evidence and unique installed resource inventory. */
internal class AdmittedKnowledgeManifest
private constructor(
    val document: KnowledgeManifestDocument,
    val evidence: KnowledgeEvidence,
    val limitations: List<KnowledgeLimitation>,
    val modules: Map<KnowledgeResourcePath, KnowledgeModuleDescriptor>,
    val guides: Map<KnowledgeResourcePath, KnowledgeGuideReference>,
) {
    fun guidesFor(sourcePath: String): List<String> =
        guides.values
            .filter { reference ->
                reference.path == "AGENTS.md" || sourcePath.startsWith(reference.path.removeSuffix("AGENTS.md"))
            }
            .map { it.resource }
            .sorted()

    companion object {
        fun admit(document: KnowledgeManifestDocument): KnowledgeAdmission<AdmittedKnowledgeManifest> {
            if (document.schemaVersion != 1)
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.UNSUPPORTED_SCHEMA)
            val evidence =
                KnowledgeEvidence.entries.singleOrNull { it.name == document.declarationEvidence }
                    ?: return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.UNSUPPORTED_EVIDENCE)
            val limitations =
                document.declarationLimitations.map { raw ->
                    KnowledgeLimitation.entries.singleOrNull { it.name == raw }
                        ?: return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.UNSUPPORTED_LIMITATION)
                }
            if (
                !validManifestIdentity(document) ||
                    !validManifestInventory(document) ||
                    !completeKnowledgeLimitations(limitations)
            )
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
            val modules =
                when (val inventory = admitModuleInventory(document.modules)) {
                    is KnowledgeAdmission.Accepted -> inventory.value
                    is KnowledgeAdmission.Rejected -> return inventory
                }
            val guides =
                when (val inventory = admitGuideInventory(document.guides)) {
                    is KnowledgeAdmission.Accepted -> inventory.value
                    is KnowledgeAdmission.Rejected -> return inventory
                }
            return KnowledgeAdmission.Accepted(
                AdmittedKnowledgeManifest(document, evidence, limitations, modules, guides)
            )
        }
    }
}

/** Module descriptors retain their manifest ownership and the exact declaration resource set. */
internal class AdmittedKnowledgeModule
private constructor(
    val document: KnowledgeModuleDocument,
    val declarations: Map<KnowledgeResourcePath, KnowledgeDeclarationDescriptor>,
) {
    companion object {
        fun admit(
            document: KnowledgeModuleDocument,
            descriptor: KnowledgeModuleDescriptor,
            manifest: AdmittedKnowledgeManifest,
        ): KnowledgeAdmission<AdmittedKnowledgeModule> {
            if (document.schemaVersion != 1)
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.UNSUPPORTED_SCHEMA)
            val directory = descriptor.projectPath.removePrefix(":").replace(':', '/')
            val expectedGuides = manifest.guidesFor("$directory/")
            if (
                !matchesModuleOwner(document, descriptor, directory) ||
                    !validDeclarationInventory(document) ||
                    !matchesModuleGuidance(document, manifest, expectedGuides)
            )
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.MODULE_REJECTED)
            val declarations = linkedMapOf<KnowledgeResourcePath, KnowledgeDeclarationDescriptor>()
            for (declaration in document.declarations) {
                if (
                    !validDeclarationDescriptor(declaration) ||
                        declaration.resource != "modules/$directory/declarations/${declaration.id}.json"
                )
                    return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.DECLARATION_REJECTED)
                when (val path = KnowledgeResourcePath.parse(declaration.resource)) {
                    is KnowledgeAdmission.Accepted -> declarations[path.value] = declaration
                    is KnowledgeAdmission.Rejected -> return path
                }
            }
            return KnowledgeAdmission.Accepted(AdmittedKnowledgeModule(document, declarations))
        }
    }
}

internal fun admitKnowledgeDeclaration(
    document: KnowledgeDeclarationDocument,
    descriptor: KnowledgeDeclarationDescriptor,
    module: AdmittedKnowledgeModule,
    manifest: AdmittedKnowledgeManifest,
): KnowledgeAdmission<KnowledgeDeclarationDocument> =
    when {
        document.schemaVersion != 1 -> KnowledgeAdmission.Rejected(KnowledgeLookupFailure.UNSUPPORTED_SCHEMA)
        !matchesDeclarationIdentity(document, descriptor) ||
            !matchesDeclarationSource(document, module) ||
            !matchesDeclarationDocumentation(document, descriptor, manifest) ->
            KnowledgeAdmission.Rejected(KnowledgeLookupFailure.DECLARATION_REJECTED)
        else -> KnowledgeAdmission.Accepted(document)
    }

internal fun admitKnowledgeGuide(
    document: KnowledgeGuideDocument,
    reference: KnowledgeGuideReference,
): KnowledgeAdmission<KnowledgeGuideDocument> =
    when {
        document.schemaVersion != 1 -> KnowledgeAdmission.Rejected(KnowledgeLookupFailure.UNSUPPORTED_SCHEMA)
        document.path != reference.path ||
            document.sha256 != reference.sha256 ||
            document.sha256 != "sha256:" + knowledgeDigest(document.content) ||
            document.scopeDirectory != document.path.removeSuffix("AGENTS.md").trimEnd('/').ifEmpty { "." } ->
            KnowledgeAdmission.Rejected(KnowledgeLookupFailure.GUIDE_REJECTED)
        else -> KnowledgeAdmission.Accepted(document)
    }

internal fun validKnowledgeSourcePath(path: String): Boolean =
    !path.contains('\\') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) }
