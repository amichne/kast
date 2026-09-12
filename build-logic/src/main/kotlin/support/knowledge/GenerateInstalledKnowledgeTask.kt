package support.knowledge

import conventions.jsoncontracts.KnowledgeDocsDocument
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import support.architecture.knowledge.ModuleKnowledgeDocument
import support.architecture.knowledge.moduleKnowledgeJson

/** Joins verified module/guide knowledge with detached PSI declaration documentation. */
@CacheableTask
abstract class GenerateInstalledKnowledgeTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleKnowledgeFile: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val documentationFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val moduleKnowledge =
            moduleKnowledgeJson.decodeFromString<ModuleKnowledgeDocument>(
                Files.readString(moduleKnowledgeFile.get().asFile.toPath())
            )
        val docs =
            JSON.decodeFromString<KnowledgeDocsDocument>(
                Files.readString(documentationFile.get().asFile.toPath())
            )
        if (docs.failures.isNotEmpty()) {
            throw GradleException("Installed knowledge input contains documentation extraction failures: ${docs.failures}")
        }
        val modules =
            moduleKnowledge.moduleGuideBindings.map { binding ->
                InstalledKnowledgeModuleInput(
                    projectPath = binding.projectPath,
                    moduleDirectory = binding.moduleDirectory,
                    governingGuidePaths = binding.governingAgentGuidePaths,
                )
            }
        val guides =
            moduleKnowledge.agentGuides.map { guide ->
                InstalledKnowledgeGuideInput(
                    path = guide.path,
                    scopeDirectory = guide.scopeDirectory,
                    content = guide.content,
                )
            }
        val declarations =
            docs.declarations.map { declaration ->
                val module = owningModule(declaration.sourcePath, modules)
                    ?: throw GradleException(
                        "Documentation declaration source is not owned by exactly one verified module: ${declaration.sourcePath}"
                    )
                InstalledKnowledgeDeclarationInput(
                    projectPath = module.projectPath,
                    sourcePath = declaration.sourcePath,
                    kind = declaration.kind,
                    name = declaration.name,
                    signature = declaration.signature,
                    documentation = declaration.documentation,
                    governingGuidePaths = governingGuides(declaration.sourcePath, guides),
                )
            }
        val result =
            InstalledKnowledgeProjection.render(
                InstalledKnowledgeInput(
                    productVersion = moduleKnowledge.productVersion,
                    sourceRevision = moduleKnowledge.sourceRevision,
                    declarationEvidence = docs.evidence,
                    declarationLimitations =
                        listOf(
                            "KOTLIN_SOURCE_ONLY",
                            "NO_TYPE_RESOLUTION",
                            "NO_INHERITED_DOCUMENTATION",
                        ),
                    modules = modules,
                    guides = guides,
                    declarations = declarations,
                )
            )
        val files =
            when (result) {
                is InstalledKnowledgeProjectionResult.Complete -> result.files
                is InstalledKnowledgeProjectionResult.Rejected ->
                    throw GradleException("Installed knowledge projection rejected: ${result.failures}")
            }
        publish(outputDirectory.get().asFile.toPath(), files)
    }

    private fun owningModule(
        sourcePath: String,
        modules: List<InstalledKnowledgeModuleInput>,
    ): InstalledKnowledgeModuleInput? {
        val candidates =
            modules.filter { module ->
                sourcePath == module.moduleDirectory || sourcePath.startsWith(module.moduleDirectory.trimEnd('/') + "/")
            }
        val longest = candidates.maxOfOrNull { it.moduleDirectory.length } ?: return null
        return candidates.singleOrNull { it.moduleDirectory.length == longest }
    }

    private fun governingGuides(
        sourcePath: String,
        guides: List<InstalledKnowledgeGuideInput>,
    ): List<String> =
        guides.filter { guide ->
            guide.scopeDirectory == "." ||
                sourcePath.startsWith(guide.scopeDirectory.trimEnd('/') + "/")
        }.sortedWith(compareBy({ it.scopeDirectory.count { character -> character == '/' } }, { it.path }))
            .map { it.path }

    private fun publish(target: Path, files: Map<String, String>) {
        val parent = target.parent
        Files.createDirectories(parent)
        val staged = Files.createTempDirectory(parent, target.fileName.toString() + ".tmp-")
        try {
            files.forEach { (relative, content) ->
                val destination = staged.resolve(relative).normalize()
                if (!destination.startsWith(staged)) {
                    throw GradleException("Installed knowledge projection escaped its output directory: $relative")
                }
                Files.createDirectories(destination.parent)
                Files.writeString(destination, content)
            }
            deleteTree(target)
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged, target)
            }
        } finally {
            if (Files.exists(staged)) deleteTree(staged)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }
    }
}
