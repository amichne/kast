package support.knowledge

import conventions.jsoncontracts.KnowledgeDocsDocument
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Joins verified module identity and scoped guides with detached PSI declaration documentation. */
@CacheableTask
abstract class GenerateInstalledKnowledgeTask : DefaultTask() {
    @get:Input abstract val productVersion: Property<String>
    @get:Input abstract val sourceRevision: Property<String>
    @get:Input abstract val moduleProjectPaths: ListProperty<String>
    @get:Input abstract val agentGuidePaths: ListProperty<String>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val agentGuideFiles: ConfigurableFileCollection

    @get:Internal abstract val repositoryDirectory: DirectoryProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val documentationFile: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = repositoryDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val revision = sourceRevision.get()
        if (!Regex("[0-9a-f]{40}").matches(revision)) {
            throw GradleException("Installed knowledge requires an exact 40-character source revision")
        }
        val docs =
            JSON.decodeFromString<KnowledgeDocsDocument>(
                Files.readString(documentationFile.get().asFile.toPath())
            )
        if (docs.failures.isNotEmpty()) {
            throw GradleException("Installed knowledge input contains documentation extraction failures: ${docs.failures}")
        }
        val guides = readGuides(root)
        val modules =
            moduleProjectPaths.get().distinct().sorted().map { projectPath ->
                val directory = projectPath.removePrefix(":").replace(':', '/')
                InstalledKnowledgeModuleInput(
                    projectPath = projectPath,
                    moduleDirectory = directory,
                    governingGuidePaths = governingGuides(directory + "/", guides),
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
                    declarationPath = declaration.declarationPath,
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
                    productVersion = productVersion.get(),
                    sourceRevision = revision,
                    declarationEvidence = docs.evidence,
                    declarationLimitations =
                        listOf(
                            "KOTLIN_SOURCE_ONLY",
                            "NAMED_DECLARATIONS_ONLY",
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

    private fun readGuides(root: Path): List<InstalledKnowledgeGuideInput> {
        val expected = agentGuidePaths.get().sorted().map { relative ->
            val path = root.resolve(relative).normalize()
            if (
                !path.startsWith(root) ||
                Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                path.toRealPath() != path
            ) {
                throw GradleException("Tracked agent guide is not a canonical regular file: $relative")
            }
            relative to path
        }
        val observed = agentGuideFiles.files.mapTo(linkedSetOf()) { it.toPath().toAbsolutePath().normalize() }
        if (observed != expected.mapTo(linkedSetOf()) { it.second }) {
            throw GradleException("Tracked installed-knowledge guide inputs do not match their Git identities")
        }
        return expected.map { (relative, path) ->
            InstalledKnowledgeGuideInput(
                path = relative,
                scopeDirectory = relative.removeSuffix("AGENTS.md").trimEnd('/').ifEmpty { "." },
                content = Files.readString(path),
            )
        }
    }

    private fun owningModule(
        sourcePath: String,
        modules: List<InstalledKnowledgeModuleInput>,
    ): InstalledKnowledgeModuleInput? {
        val candidates =
            modules.filter { module -> sourcePath.startsWith(module.moduleDirectory.trimEnd('/') + "/") }
        val longest = candidates.maxOfOrNull { it.moduleDirectory.length } ?: return null
        return candidates.singleOrNull { it.moduleDirectory.length == longest }
    }

    private fun governingGuides(
        sourcePath: String,
        guides: List<InstalledKnowledgeGuideInput>,
    ): List<String> =
        guides.filter { guide ->
            guide.scopeDirectory == "." || sourcePath.startsWith(guide.scopeDirectory.trimEnd('/') + "/")
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
