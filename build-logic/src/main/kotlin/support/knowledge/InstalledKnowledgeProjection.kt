package support.knowledge

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class InstalledKnowledgeInput(
    val productVersion: String,
    val sourceRevision: String,
    val declarationEvidence: String,
    val declarationLimitations: List<String>,
    val modules: List<InstalledKnowledgeModuleInput>,
    val guides: List<InstalledKnowledgeGuideInput>,
    val declarations: List<InstalledKnowledgeDeclarationInput>,
)

internal data class InstalledKnowledgeModuleInput(
    val projectPath: String,
    val moduleDirectory: String,
    val governingGuidePaths: List<String>,
)

internal data class InstalledKnowledgeGuideInput(
    val path: String,
    val scopeDirectory: String,
    val content: String,
)

internal data class InstalledKnowledgeDeclarationInput(
    val projectPath: String,
    val sourcePath: String,
    val declarationPath: String,
    val kind: String,
    val name: String,
    val signature: String,
    val documentation: String,
    val governingGuidePaths: List<String>,
)

internal sealed interface InstalledKnowledgeProjectionResult {
    data class Complete(val files: Map<String, String>) : InstalledKnowledgeProjectionResult
    data class Rejected(val failures: List<InstalledKnowledgeProjectionFailure>) : InstalledKnowledgeProjectionResult
}

internal sealed interface InstalledKnowledgeProjectionFailure {
    data class DuplicateModule(val projectPath: String) : InstalledKnowledgeProjectionFailure
    data class DuplicateGuide(val path: String) : InstalledKnowledgeProjectionFailure
    data class UnknownGuide(val projectPath: String, val guidePath: String) : InstalledKnowledgeProjectionFailure
    data class UnknownDeclarationModule(val projectPath: String, val sourcePath: String) : InstalledKnowledgeProjectionFailure
    data class DuplicateDeclaration(val id: String) : InstalledKnowledgeProjectionFailure
}

internal object InstalledKnowledgeProjection {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun render(input: InstalledKnowledgeInput): InstalledKnowledgeProjectionResult {
        val failures = mutableListOf<InstalledKnowledgeProjectionFailure>()
        input.modules.groupingBy { it.projectPath }.eachCount().filterValues { it > 1 }.keys.forEach {
            failures += InstalledKnowledgeProjectionFailure.DuplicateModule(it)
        }
        input.guides.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys.forEach {
            failures += InstalledKnowledgeProjectionFailure.DuplicateGuide(it)
        }
        val modules = input.modules.associateBy { it.projectPath }
        val guides = input.guides.associateBy { it.path }
        input.modules.forEach { module ->
            module.governingGuidePaths.filterNot(guides::containsKey).forEach { guidePath ->
                failures += InstalledKnowledgeProjectionFailure.UnknownGuide(module.projectPath, guidePath)
            }
        }
        input.declarations.forEach { declaration ->
            if (!modules.containsKey(declaration.projectPath)) {
                failures += InstalledKnowledgeProjectionFailure.UnknownDeclarationModule(
                    declaration.projectPath,
                    declaration.sourcePath,
                )
            }
            declaration.governingGuidePaths.filterNot(guides::containsKey).forEach { guidePath ->
                failures += InstalledKnowledgeProjectionFailure.UnknownGuide(declaration.projectPath, guidePath)
            }
        }
        val declarationIds = input.declarations.map(::declarationId)
        declarationIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            failures += InstalledKnowledgeProjectionFailure.DuplicateDeclaration(it)
        }
        if (failures.isNotEmpty()) return InstalledKnowledgeProjectionResult.Rejected(failures.distinct())

        val files = linkedMapOf<String, String>()
        input.guides.sortedBy { it.path }.forEach { guide ->
            val resource = guideResource(guide.path)
            files[resource] = json.encodeToString(
                InstalledKnowledgeGuide(
                    path = guide.path,
                    scopeDirectory = guide.scopeDirectory,
                    sha256 = sha256(guide.content),
                    content = guide.content,
                ),
            ) + "\n"
        }

        val moduleDescriptors = input.modules.sortedBy { it.projectPath }.map { module ->
            val moduleResource = moduleResource(module.projectPath)
            val moduleGuides = module.governingGuidePaths.sorted().map { guidePath ->
                val guide = requireNotNull(guides[guidePath])
                InstalledKnowledgeGuideReference(guide.path, sha256(guide.content), guideResource(guide.path))
            }
            val declarations = input.declarations.filter { it.projectPath == module.projectPath }
                .sortedWith(compareBy({ it.declarationPath }, { it.signature }, { it.sourcePath }))
                .map { declaration ->
                    val id = declarationId(declaration)
                    val resource = declarationResource(module.projectPath, id)
                    val declarationGuides = declaration.governingGuidePaths.sorted().map(::guideResource)
                    files[resource] = json.encodeToString(
                        InstalledKnowledgeDeclaration(
                            id = id,
                            projectPath = declaration.projectPath,
                            sourcePath = declaration.sourcePath,
                            declarationPath = declaration.declarationPath,
                            kind = declaration.kind,
                            name = declaration.name,
                            signature = declaration.signature,
                            documentation = declaration.documentation,
                            governingGuides = declarationGuides,
                        ),
                    ) + "\n"
                    InstalledKnowledgeDeclarationDescriptor(
                        id = id,
                        declarationPath = declaration.declarationPath,
                        name = declaration.name,
                        kind = declaration.kind,
                        summary = firstParagraph(declaration.documentation),
                        resource = resource,
                    )
                }
            files[moduleResource] = json.encodeToString(
                InstalledKnowledgeModule(
                    projectPath = module.projectPath,
                    moduleDirectory = module.moduleDirectory,
                    governingGuides = moduleGuides,
                    declarations = declarations,
                ),
            ) + "\n"
            InstalledKnowledgeModuleDescriptor(module.projectPath, moduleResource)
        }

        files["manifest.json"] = json.encodeToString(
            InstalledKnowledgeManifest(
                productVersion = input.productVersion,
                sourceRevision = input.sourceRevision,
                declarationEvidence = input.declarationEvidence,
                declarationLimitations = input.declarationLimitations.sorted(),
                modules = moduleDescriptors,
            ),
        ) + "\n"
        return InstalledKnowledgeProjectionResult.Complete(files.toSortedMap())
    }

    private fun declarationId(declaration: InstalledKnowledgeDeclarationInput): String =
        sha256(
            "${declaration.projectPath}\u0000${declaration.sourcePath}\u0000${declaration.declarationPath}" +
                "\u0000${declaration.kind}\u0000${declaration.signature}"
        ).removePrefix("sha256:")

    private fun moduleResource(projectPath: String): String =
        "modules/${projectPath.removePrefix(":").replace(':', '/')}/index.json"

    private fun declarationResource(projectPath: String, id: String): String =
        "modules/${projectPath.removePrefix(":").replace(':', '/')}/declarations/$id.json"

    private fun guideResource(path: String): String =
        "guides/${path.removeSuffix("AGENTS.md").trimEnd('/').ifEmpty { "root" }}.json"

    private fun firstParagraph(documentation: String): String =
        documentation.trim().split(Regex("\\n\\s*\\n"), limit = 2).firstOrNull().orEmpty()
            .replace(Regex("\\s+"), " ")
            .take(240)

    private fun sha256(value: String): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
