package support.knowledge

import conventions.jsoncontracts.KnowledgeDocsDocument
import conventions.jsoncontracts.knowledgeDocsJson
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        val docs = when (val document = knowledgeDocsJson.decodeFromString<KnowledgeDocsDocument>(
            Files.readString(documentationFile.get().asFile.toPath())
        )) {
            is KnowledgeDocsDocument.Complete -> document
            is KnowledgeDocsDocument.Rejected -> throw GradleException(
                "Installed knowledge input contains documentation extraction failures: ${document.failures}"
            )
        }
        if (docs.schemaVersion != 1) {
            throw GradleException("Unsupported documentation inventory schema version: ${docs.schemaVersion}")
        }
        val guides = readGuides(root)
        val modules =
            moduleProjectPaths.get().sorted().map { projectPath ->
                val directory = projectPath.removePrefix(":").replace(':', '/')
                InstalledKnowledgeModuleInput(
                    projectPath = projectPath,
                    moduleDirectory = directory,
                    governingGuidePaths = installedKnowledgeGoverningGuides(directory + "/", guides),
                )
            }
        val declarations =
            docs.declarations.map { declaration ->
                val module = when (val ownership = installedKnowledgeOwningModule(declaration.sourcePath, modules)) {
                    is InstalledKnowledgeModuleOwnership.Owned -> ownership.module
                    is InstalledKnowledgeModuleOwnership.Rejected -> throw GradleException(
                        "Documentation module ownership rejected: ${ownership.failure} (${declaration.sourcePath})"
                    )
                }
                InstalledKnowledgeDeclarationInput(
                    projectPath = module.projectPath,
                    sourcePath = declaration.sourcePath,
                    declarationPath = declaration.declarationPath,
                    kind = declaration.kind,
                    name = declaration.name,
                    signature = declaration.signature,
                    documentation = declaration.documentation,
                    governingGuidePaths = installedKnowledgeGoverningGuides(declaration.sourcePath, guides),
                )
            }
        val result =
            InstalledKnowledgeProjection.render(
                InstalledKnowledgeInput(
                    productVersion = productVersion.get(),
                    sourceRevision = revision,
                    declarationEvidence = docs.evidence,
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
}
