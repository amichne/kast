package io.github.amichne.kast.cli.knowledge

import io.github.amichne.kast.cli.CliJsonDocument
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val MAX_RESULTS = 20
private const val MAX_SELECTION_BYTES = 4096

@JvmInline
internal value class KnowledgeSelection private constructor(val value: String) {
    companion object {
        fun parse(raw: String): KnowledgeSelection? =
            raw.trim().takeIf { value ->
                value.isNotEmpty() && value.encodeToByteArray().size <= MAX_SELECTION_BYTES
            }?.let(::KnowledgeSelection)
    }
}

internal enum class KnowledgeLookupFailure {
    BUNDLE_UNAVAILABLE,
    BUNDLE_REJECTED,
    RESOURCE_UNAVAILABLE,
    RESOURCE_REJECTED,
}

internal sealed interface KnowledgeLookup {
    data class Complete(val document: CliJsonDocument) : KnowledgeLookup
    data class Rejected(val failure: KnowledgeLookupFailure) : KnowledgeLookup
}

internal fun interface KnowledgeReader {
    fun lookup(selection: KnowledgeSelection): KnowledgeLookup
}

internal object UnavailableKnowledgeReader : KnowledgeReader {
    override fun lookup(selection: KnowledgeSelection): KnowledgeLookup =
        KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
}

/** Discovers the sibling installed resource tree from the CLI code source only when knowledge is requested. */
internal object DiscoveringInstalledKnowledgeReader : KnowledgeReader {
    override fun lookup(selection: KnowledgeSelection): KnowledgeLookup {
        val codeSource =
            try {
                Path.of(DiscoveringInstalledKnowledgeReader::class.java.protectionDomain.codeSource.location.toURI())
                    .toRealPath()
            } catch (_: IOException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            } catch (_: URISyntaxException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            }
        val libraryDirectory = codeSource.parent?.takeIf { it.fileName.toString() == "lib" }
            ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
        val productRoot = libraryDirectory.parent
            ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
        val knowledgeRoot = productRoot.resolve("share/kast/knowledge")
        if (Files.isSymbolicLink(knowledgeRoot)) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
        }
        val physical =
            try {
                knowledgeRoot.toRealPath()
            } catch (_: IOException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            }
        if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS) || physical != knowledgeRoot) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
        }
        return InstalledKnowledgeReader(physical).lookup(selection)
    }
}

/** Reads only immutable resources under one admitted installed knowledge root. */
internal class InstalledKnowledgeReader(private val root: Path) : KnowledgeReader {
    override fun lookup(selection: KnowledgeSelection): KnowledgeLookup {
        val manifest = readManifest() ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
        val exact = exactResource(selection.value)
        if (exact != null) return exact

        val query = selection.value.lowercase()
        val matches = mutableListOf<ScoredKnowledgeItem>()
        for (module in manifest.modules) {
            val index = readModule(module.resource)
                ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
            for (declaration in index.declarations) {
                score(query, index.projectPath, declaration)?.let { scored -> matches += scored }
            }
        }
        val items =
            matches.sortedWith(
                compareBy<ScoredKnowledgeItem>({ it.score }, { it.item.name.lowercase() }, { it.item.resource })
            ).take(MAX_RESULTS).map(ScoredKnowledgeItem::item)
        return KnowledgeLookup.Complete(
            searchFactory.create(
                KnowledgeSearchDocument(
                    query = selection.value,
                    declarationEvidence = manifest.declarationEvidence,
                    declarationLimitations = manifest.declarationLimitations,
                    items = items,
                )
            )
        )
    }

    private fun exactResource(raw: String): KnowledgeLookup? {
        if (!looksLikeResource(raw)) return null
        val relative = Path.of(raw)
        if (relative.isAbsolute || relative.any { it.toString() == ".." }) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        }
        val candidate = root.resolve(relative).normalize()
        if (!candidate.startsWith(root)) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        }
        val admitted = admittedRegularFile(candidate)
            ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_UNAVAILABLE)
        val text = try {
            Files.readString(admitted)
        } catch (_: IOException) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_UNAVAILABLE)
        }
        val objectValue = try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        return KnowledgeLookup.Complete(resourceFactory.create(KnowledgeResourceDocument(raw, objectValue)))
    }

    private fun readManifest(): KnowledgeManifestDocument? =
        readDocument("manifest.json", KnowledgeManifestDocument.serializer())

    private fun readModule(resource: String): KnowledgeModuleDocument? =
        readDocument(resource, KnowledgeModuleDocument.serializer())

    private fun <T> readDocument(relative: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val candidate = root.resolve(relative).normalize()
        if (!candidate.startsWith(root)) return null
        val file = admittedRegularFile(candidate) ?: return null
        return try {
            JSON.decodeFromString(serializer, Files.readString(file))
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun admittedRegularFile(path: Path): Path? {
        if (Files.isSymbolicLink(path)) return null
        val physical = try {
            path.toRealPath()
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (!physical.startsWith(root)) return null
        return physical.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun score(
        query: String,
        projectPath: String,
        declaration: KnowledgeDeclarationDescriptor,
    ): ScoredKnowledgeItem? {
        val name = declaration.name.lowercase()
        val summary = declaration.summary.lowercase()
        val module = projectPath.lowercase()
        val score = when {
            name == query -> 0
            name.startsWith(query) -> 1
            query in name -> 2
            query in summary -> 3
            query in module -> 4
            else -> return null
        }
        return ScoredKnowledgeItem(
            score,
            KnowledgeSearchItem(
                kind = declaration.kind,
                name = declaration.name,
                module = projectPath,
                summary = declaration.summary,
                resource = declaration.resource,
            ),
        )
    }

    private companion object {
        val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

private fun looksLikeResource(raw: String): Boolean =
    raw == "manifest.json" || raw.startsWith("modules/") || raw.startsWith("guides/")

private data class ScoredKnowledgeItem(val score: Int, val item: KnowledgeSearchItem)

@Serializable
private data class KnowledgeManifestDocument(
    val schemaVersion: Int,
    val productVersion: String,
    val sourceRevision: String,
    val declarationEvidence: String,
    val declarationLimitations: List<String>,
    val modules: List<KnowledgeModuleDescriptor>,
)

@Serializable
private data class KnowledgeModuleDescriptor(val projectPath: String, val resource: String)

@Serializable
private data class KnowledgeModuleDocument(
    val schemaVersion: Int,
    val projectPath: String,
    val moduleDirectory: String,
    val governingGuides: List<KnowledgeGuideReference>,
    val declarations: List<KnowledgeDeclarationDescriptor>,
)

@Serializable
private data class KnowledgeGuideReference(
    val path: String,
    val sha256: String,
    val resource: String,
)

@Serializable
private data class KnowledgeDeclarationDescriptor(
    val id: String,
    val name: String,
    val kind: String,
    val summary: String,
    val resource: String,
)

@Serializable
private data class KnowledgeSearchDocument(
    val operation: String = "knowledge",
    val status: String = "complete",
    val query: String,
    val declarationEvidence: String,
    val declarationLimitations: List<String>,
    val items: List<KnowledgeSearchItem>,
)

@Serializable
private data class KnowledgeSearchItem(
    val kind: String,
    val name: String,
    val module: String,
    val summary: String,
    val resource: String,
)

@Serializable
private data class KnowledgeResourceDocument(
    val operation: String = "knowledge",
    val status: String = "complete",
    val resource: String,
    val document: JsonObject,
)

private val searchFactory = CliJsonDocument.generated(KnowledgeSearchDocument.serializer())
private val resourceFactory = CliJsonDocument.generated(KnowledgeResourceDocument.serializer())
