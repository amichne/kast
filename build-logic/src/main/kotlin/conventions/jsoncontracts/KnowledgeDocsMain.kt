package conventions.jsoncontracts

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Standalone PSI extraction process; compiler implementation classes never enter Gradle's classloader. */
object KnowledgeDocsMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected one generated documentation extraction request." }
        val request = Json.decodeFromString<KnowledgeDocsRequest>(Files.readString(Path.of(arguments.single())))
        val document = extractKnowledgeDocs(request)
        val output = Path.of(request.output)
        Files.createDirectories(output.parent)
        Files.writeString(output, knowledgeDocsJson.encodeToString(document) + "\n")
        if (document is KnowledgeDocsDocument.Rejected) exitProcess(1)
    }
}

/** Filesystem extraction boundary: any rejected source prevents publication of a success-shaped inventory. */
fun extractKnowledgeDocs(request: KnowledgeDocsRequest): KnowledgeDocsDocument {
        val root = Path.of(request.repositoryRoot).toAbsolutePath().normalize()
        val declarations = mutableListOf<KnowledgeDeclarationDocument>()
        val failures = mutableListOf<KnowledgeDocsFailure>()
        KotlinDocumentationScanner().use { scanner ->
            request.sources.sorted().forEach { sourcePath ->
                val path = root.resolve(sourcePath).normalize()
                if (!path.startsWith(root) || !sourcePath.endsWith(".kt")) {
                    failures += KnowledgeDocsFailure(sourcePath, KnowledgeDocsFailureCode.UNSUPPORTED_SOURCE)
                    return@forEach
                }
                val content =
                    try {
                        if (Files.isSymbolicLink(path) || path.toRealPath() != path) {
                            failures += KnowledgeDocsFailure(sourcePath, KnowledgeDocsFailureCode.NON_CANONICAL_SOURCE)
                            return@forEach
                        }
                        Files.readString(path)
                    } catch (_: IOException) {
                        failures += KnowledgeDocsFailure(sourcePath, KnowledgeDocsFailureCode.UNREADABLE_SOURCE)
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
        if (failures.isNotEmpty()) {
            return KnowledgeDocsDocument.Rejected(failures = failures.sortedWith(compareBy({ it.sourcePath }, { it.reason })))
        }
        return KnowledgeDocsDocument.Complete(
            declarations = declarations.sortedWith(
                compareBy(
                    KnowledgeDeclarationDocument::sourcePath,
                    KnowledgeDeclarationDocument::declarationPath,
                    KnowledgeDeclarationDocument::signature,
                ),
            ),
        )
}
