package io.github.amichne.kast.cli.knowledge

import java.security.MessageDigest
import kotlinx.serialization.Serializable

internal const val MAX_KNOWLEDGE_RESOURCE_BYTES = 8 * 1024 * 1024
internal const val MAX_KNOWLEDGE_MODULES = 512
internal const val MAX_KNOWLEDGE_DECLARATIONS = 20_000
internal const val MAX_KNOWLEDGE_GUIDES = 4096
internal const val MAX_KNOWLEDGE_SELECTION_BYTES = 4096
internal const val MAX_KNOWLEDGE_SUMMARY_CHARACTERS = 240
private const val BYTE_MASK = 0xff

internal enum class KnowledgeLookupFailure {
    BUNDLE_UNAVAILABLE,
    BUNDLE_REJECTED,
    RESOURCE_UNAVAILABLE,
    RESOURCE_REJECTED,
    RESOURCE_TOO_LARGE,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_EVIDENCE,
    UNSUPPORTED_LIMITATION,
    MODULE_REJECTED,
    DECLARATION_REJECTED,
    GUIDE_REJECTED,
}

internal sealed interface KnowledgeAdmission<out T> {
    data class Accepted<T>(val value: T) : KnowledgeAdmission<T>

    data class Rejected(val failure: KnowledgeLookupFailure) : KnowledgeAdmission<Nothing>
}

@Serializable
internal enum class KnowledgeEvidence {
    KOTLIN_PSI_SYNTAX
}

@Serializable
internal enum class KnowledgeLimitation {
    KOTLIN_SOURCE_ONLY,
    NAMED_DECLARATIONS_ONLY,
    NO_TYPE_RESOLUTION,
    NO_INHERITED_DOCUMENTATION,
}

/** Canonical resource identity; no filesystem operation accepts an unchecked selector. */
@JvmInline
internal value class KnowledgeResourcePath private constructor(val value: String) {
    companion object {
        val manifest = KnowledgeResourcePath("manifest.json")

        fun parse(raw: String): KnowledgeAdmission<KnowledgeResourcePath> =
            if (raw.length <= MAX_KNOWLEDGE_SELECTION_BYTES && hasResourceNamespace(raw) && hasResourceComponents(raw))
                KnowledgeAdmission.Accepted(KnowledgeResourcePath(raw))
            else KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)

        private fun hasResourceNamespace(raw: String): Boolean =
            raw == "manifest.json" || raw.startsWith("modules/") || raw.startsWith("guides/")

        private fun hasResourceComponents(raw: String): Boolean =
            raw.endsWith(".json") && raw.split('/').all { it != "." && it != ".." && COMPONENT.matches(it) }

        private val COMPONENT = Regex("[A-Za-z0-9_.-]+")
    }
}

internal fun knowledgeDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { byte ->
        "%02x".format(byte.toInt() and BYTE_MASK)
    }

internal fun knowledgeSummary(documentation: String): String =
    documentation
        .trim()
        .split(Regex("\\n\\s*\\n"), limit = 2)
        .first()
        .replace(Regex("\\s+"), " ")
        .take(MAX_KNOWLEDGE_SUMMARY_CHARACTERS)

internal fun knowledgeGuideResource(path: String): String =
    "guides/${path.removeSuffix("AGENTS.md").trimEnd('/').ifEmpty { "root" }}.json"

internal fun knowledgeModuleResource(projectPath: String): String =
    "modules/${projectPath.removePrefix(":").replace(':', '/')}/index.json"

internal fun knowledgeDeclarationId(card: KnowledgeDeclarationDocument): String =
    knowledgeDigest(
        "${card.projectPath}\u0000${card.sourcePath}\u0000${card.declarationPath}" +
            "\u0000${card.kind}\u0000${card.signature}"
    )

internal val KNOWLEDGE_DIGEST = Regex("[0-9a-f]{64}")
internal val KNOWLEDGE_PROJECT = Regex(":[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")
internal val KNOWLEDGE_KINDS =
    setOf("class", "interface", "enum", "enum-entry", "annotation", "object", "function", "property", "variable", "typealias")
