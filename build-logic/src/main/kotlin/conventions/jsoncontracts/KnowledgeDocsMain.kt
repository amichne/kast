package conventions.jsoncontracts

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class KnowledgeDocsRequest(
    val repositoryRoot: String,
    val sources: List<String>,
    val output: String,
)

@Serializable
data class KnowledgeDeclarationDocument(
    val sourcePath: String,
    val declarationPath: String,
    val kind: String,
    val name: String,
    val signature: String,
    val documentation: String,
)

@Serializable
data class KnowledgeDocsFailure(
    val sourcePath: String,
    val reason: String,
)

@Serializable
data class KnowledgeDocsDocument(
    val schemaVersion: Int = 1,
    val evidence: String = "KOTLIN_PSI_SYNTAX",
    val declarations: List<KnowledgeDeclarationDocument>,
    val failures: List<KnowledgeDocsFailure>,
)

/** Standalone PSI extraction process; compiler implementation classes never enter Gradle's classloader. */
object KnowledgeDocsMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected one generated documentation extraction request." }
        val request = Json.decodeFromString<KnowledgeDocsRequest>(Files.readString(Path.of(arguments.single())))
        val root = Path.of(request.repositoryRoot).toAbsolutePath().normalize()
        val declarations = mutableListOf<KnowledgeDeclarationDocument>()
        val failures = mutableListOf<KnowledgeDocsFailure>()
        KotlinDocumentationScanner().use { scanner ->
            request.sources.sorted().forEach { sourcePath ->
                val path = root.resolve(sourcePath).normalize()
                if (!path.startsWith(root) || !sourcePath.endsWith(".kt")) {
                    failures += KnowledgeDocsFailure(sourcePath, "unsupported-source")
                    return@forEach
                }
                val content =
                    try {
                        Files.readString(path)
                    } catch (_: IOException) {
                        failures += KnowledgeDocsFailure(sourcePath, "unreadable-source")
                        return@forEach
                    }
                when (val scan = scanner.scan(path.fileName.toString(), content)) {
                    is KotlinDocumentationScan.Accepted ->
                        scan.declarations.forEach { declaration ->
                            declarations +=
                                KnowledgeDeclarationDocument(
                                    sourcePath = sourcePath,
                                    declarationPath = declaration.declarationPath,
                                    kind = declaration.kind,
                                    name = declaration.name,
                                    signature = declaration.signature,
                                    documentation = declaration.documentation,
                                )
                        }
                    is KotlinDocumentationScan.Rejected ->
                        failures += KnowledgeDocsFailure(sourcePath, scan.reason)
                }
            }
        }
        val document =
            KnowledgeDocsDocument(
                declarations = declarations.sortedWith(
                    compareBy(
                        KnowledgeDeclarationDocument::sourcePath,
                        KnowledgeDeclarationDocument::declarationPath,
                        KnowledgeDeclarationDocument::signature,
                    ),
                ),
                failures = failures.sortedWith(compareBy({ it.sourcePath }, { it.reason })),
            )
        val output = Path.of(request.output)
        Files.createDirectories(output.parent)
        Files.writeString(output, JSON.encodeToString(document) + "\n")
        if (document.failures.isNotEmpty()) exitProcess(1)
    }

    private val JSON = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }
}
