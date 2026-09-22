package io.github.amichne.kast.cli.knowledge

import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

private const val MAX_RESULTS = 20

internal sealed interface KnowledgeLookup {
    data class Complete(val document: CanonicalJsonDocument) : KnowledgeLookup

    data class Rejected(val failure: KnowledgeLookupFailure) : KnowledgeLookup
}

internal fun interface KnowledgeReader {
    fun lookup(selection: KnowledgeSelection): KnowledgeLookup
}

/** Reads shallow search descriptors or one typed resource from the admitted installed bundle. */
internal class InstalledKnowledgeReader(root: Path) : KnowledgeReader {
    private val resources = KnowledgeResources(root)

    override fun lookup(selection: KnowledgeSelection): KnowledgeLookup {
        val document =
            when (val read = resources.read(KnowledgeResourcePath.manifest, KnowledgeManifestDocument.serializer())) {
                is KnowledgeAdmission.Accepted -> read.value
                is KnowledgeAdmission.Rejected -> return KnowledgeLookup.Rejected(read.failure)
            }
        val manifest =
            when (val admission = AdmittedKnowledgeManifest.admit(document)) {
                is KnowledgeAdmission.Accepted -> admission.value
                is KnowledgeAdmission.Rejected -> return KnowledgeLookup.Rejected(admission.failure)
            }
        if (selection.value.endsWith(".json") || '/' in selection.value) {
            return when (val path = KnowledgeResourcePath.parse(selection.value)) {
                is KnowledgeAdmission.Accepted -> exactResource(path.value, manifest)
                is KnowledgeAdmission.Rejected -> KnowledgeLookup.Rejected(path.failure)
            }
        }
        return search(selection, manifest)
    }

    private fun search(selection: KnowledgeSelection, manifest: AdmittedKnowledgeManifest): KnowledgeLookup {
        val matches = mutableListOf<ScoredKnowledgeItem>()
        for ((path, descriptor) in manifest.modules) {
            val module =
                when (val read = readModule(path, descriptor, manifest)) {
                    is KnowledgeAdmission.Accepted -> read.value
                    is KnowledgeAdmission.Rejected -> return KnowledgeLookup.Rejected(read.failure)
                }
            for (declaration in module.declarations.values) {
                score(selection.value.lowercase(), module.document.projectPath, declaration)?.let { matches += it }
                matches.sortWith(SCORED_ORDER)
                if (matches.size > MAX_RESULTS) matches.removeAt(MAX_RESULTS)
            }
        }
        return KnowledgeLookup.Complete(
            searchFactory.create(
                KnowledgeSearchDocument(
                    query = selection.value,
                    declarationEvidence = manifest.evidence,
                    declarationLimitations = manifest.limitations,
                    items = matches.map(ScoredKnowledgeItem::item),
                )
            )
        )
    }

    private fun exactResource(path: KnowledgeResourcePath, manifest: AdmittedKnowledgeManifest): KnowledgeLookup {
        if (path == KnowledgeResourcePath.manifest) {
            return resourceResult(
                path,
                KnowledgeAdmission.Accepted(manifest.document),
                KnowledgeManifestDocument.serializer(),
            )
        }
        manifest.guides[path]?.let { reference ->
            val guide =
                when (val read = resources.read(path, KnowledgeGuideDocument.serializer())) {
                    is KnowledgeAdmission.Accepted -> admitKnowledgeGuide(read.value, reference)
                    is KnowledgeAdmission.Rejected -> read
                }
            return resourceResult(path, guide, KnowledgeGuideDocument.serializer())
        }
        val owner =
            manifest.modules.entries.singleOrNull { (modulePath, _) ->
                path == modulePath ||
                    path.value.startsWith(modulePath.value.removeSuffix("index.json") + "declarations/")
            } ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        val module =
            when (val read = readModule(owner.key, owner.value, manifest)) {
                is KnowledgeAdmission.Accepted -> read.value
                is KnowledgeAdmission.Rejected -> return KnowledgeLookup.Rejected(read.failure)
            }
        if (path == owner.key) {
            return resourceResult(
                path,
                KnowledgeAdmission.Accepted(module.document),
                KnowledgeModuleDocument.serializer(),
            )
        }
        val descriptor =
            module.declarations[path] ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        val declaration =
            when (val read = resources.read(path, KnowledgeDeclarationDocument.serializer())) {
                is KnowledgeAdmission.Accepted -> admitKnowledgeDeclaration(read.value, descriptor, module, manifest)
                is KnowledgeAdmission.Rejected -> read
            }
        return resourceResult(path, declaration, KnowledgeDeclarationDocument.serializer())
    }

    private fun readModule(
        path: KnowledgeResourcePath,
        descriptor: KnowledgeModuleDescriptor,
        manifest: AdmittedKnowledgeManifest,
    ): KnowledgeAdmission<AdmittedKnowledgeModule> =
        when (val read = resources.read(path, KnowledgeModuleDocument.serializer())) {
            is KnowledgeAdmission.Accepted -> AdmittedKnowledgeModule.admit(read.value, descriptor, manifest)
            is KnowledgeAdmission.Rejected -> read
        }
}

private fun <T> resourceResult(
    path: KnowledgeResourcePath,
    result: KnowledgeAdmission<T>,
    serializer: KSerializer<T>,
): KnowledgeLookup =
    when (result) {
        is KnowledgeAdmission.Accepted ->
            KnowledgeLookup.Complete(
                CanonicalJsonDocument.generated(KnowledgeResourceDocument.serializer(serializer))
                    .create(KnowledgeResourceDocument(resource = path.value, document = result.value))
            )
        is KnowledgeAdmission.Rejected -> KnowledgeLookup.Rejected(result.failure)
    }

private fun score(
    query: String,
    projectPath: String,
    declaration: KnowledgeDeclarationDescriptor,
): ScoredKnowledgeItem? {
    val name = declaration.name.lowercase()
    val path = declaration.declarationPath.lowercase()
    val score =
        when {
            name == query || path == query -> 0
            name.startsWith(query) || path.startsWith(query) -> 1
            query in name || query in path -> 2
            query in declaration.summary.lowercase() -> 3
            query in projectPath.lowercase() -> 4
            else -> return null
        }
    return ScoredKnowledgeItem(
        score,
        KnowledgeSearchItem(
            kind = declaration.kind,
            name = declaration.name,
            declarationPath = declaration.declarationPath,
            module = projectPath,
            summary = declaration.summary,
            resource = declaration.resource,
        ),
    )
}

private data class ScoredKnowledgeItem(val score: Int, val item: KnowledgeSearchItem)

private val SCORED_ORDER =
    compareBy<ScoredKnowledgeItem>({ it.score }, { it.item.declarationPath.lowercase() }, { it.item.resource })

@Serializable
private data class KnowledgeSearchDocument(
    val operation: String = "knowledge",
    val status: String = "complete",
    val query: String,
    val declarationEvidence: KnowledgeEvidence,
    val declarationLimitations: List<KnowledgeLimitation>,
    val items: List<KnowledgeSearchItem>,
)

@Serializable
private data class KnowledgeSearchItem(
    val kind: String,
    val name: String,
    val declarationPath: String,
    val module: String,
    val summary: String,
    val resource: String,
)

@Serializable
private data class KnowledgeResourceDocument<T>(
    val operation: String = "knowledge",
    val status: String = "complete",
    val resource: String,
    val document: T,
)

private val searchFactory = CanonicalJsonDocument.generated(KnowledgeSearchDocument.serializer())
