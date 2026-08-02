package io.github.amichne.kast.api.client.fields

import io.github.amichne.kast.api.client.WorkspaceRelativePath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.regex.Pattern

@Serializable(with = WorkspaceIndexingPatternSerializer::class)
class WorkspaceIndexingPattern private constructor(
    private val source: String,
    private val regex: Regex,
) {
    fun matches(relativePath: WorkspaceRelativePath): Boolean = regex.matches(relativePath.value)

    override fun equals(other: Any?): Boolean = other is WorkspaceIndexingPattern && source == other.source

    override fun hashCode(): Int = source.hashCode()

    override fun toString(): String = source

    companion object {
        fun parse(raw: String): WorkspaceIndexingPattern {
            val compiled = compileWorkspacePattern(raw, WorkspacePatternSyntax.CONFIGURED_VALUE)
            return WorkspaceIndexingPattern(compiled.source, compiled.regex)
        }
    }
}

class WorkspaceIgnorePattern private constructor(
    private val source: String,
    private val regex: Regex,
) {
    fun matches(relativePath: WorkspaceRelativePath): Boolean = regex.matches(relativePath.value)

    companion object {
        fun parseDirectiveBody(raw: String): WorkspaceIgnorePattern {
            val compiled = compileWorkspacePattern(raw, WorkspacePatternSyntax.IGNORE_DIRECTIVE_BODY)
            return WorkspaceIgnorePattern(compiled.source, compiled.regex)
        }
    }
}

object WorkspaceIndexingPatternSerializer : KSerializer<WorkspaceIndexingPattern> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WorkspaceIndexingPattern", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: WorkspaceIndexingPattern) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): WorkspaceIndexingPattern =
        WorkspaceIndexingPattern.parse(decoder.decodeString())
}

private data class CompiledWorkspacePattern(
    val source: String,
    val regex: Regex,
)

private enum class WorkspacePatternSyntax {
    CONFIGURED_VALUE,
    IGNORE_DIRECTIVE_BODY,
}

private fun compileWorkspacePattern(
    raw: String,
    syntax: WorkspacePatternSyntax,
): CompiledWorkspacePattern {
    val source = raw.removePrefix("\uFEFF").trimUnescapedTrailingSpaces()
    require(source.isNotBlank()) { "Workspace indexing pattern must not be blank" }
    if (syntax == WorkspacePatternSyntax.CONFIGURED_VALUE) {
        require(!source.trimStart().startsWith("#")) {
            "Workspace indexing pattern must not be a comment: $raw"
        }
        require(!source.trimStart().startsWith("!")) {
            "Workspace indexing pattern must be positive: $raw"
        }
    }
    validateRepositoryRelativePattern(source)

    val anchored = source.startsWith("/")
    val body = source.removePrefix("/").removeSuffix("/")
    require(body.isNotEmpty()) { "Workspace indexing pattern must not be empty" }
    val prefix = if (anchored || '/' in body) "^" else "^(?:.*/)?"
    val regex = try {
        Regex(prefix + body.toGitIgnoreRegex() + "(?:/.*)?$")
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid workspace indexing pattern $raw: ${error.message}", error)
    }
    return CompiledWorkspacePattern(source, regex)
}

private fun validateRepositoryRelativePattern(pattern: String) {
    require(!Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(pattern)) {
        "Workspace indexing pattern must be repository-relative, not a filesystem path: $pattern"
    }
    require(!pattern.startsWith("//")) {
        "Workspace indexing pattern must be repository-relative, not a filesystem path: $pattern"
    }

    val segments = pattern.removePrefix("/").replace('\\', '/').split('/')
    require(segments.none { segment -> segment == ".." }) {
        "Workspace indexing pattern must not contain parent traversal: $pattern"
    }
    val systemRoot = segments.firstOrNull()?.lowercase() in setOf(
        "users",
        "home",
        "volumes",
        "private",
        "var",
        "tmp",
        "opt",
        "usr",
        "etc",
    )
    require(!(pattern.startsWith("/") && systemRoot && segments.size > 2)) {
        "Workspace indexing pattern must be repository-relative, not a filesystem path: $pattern"
    }
}

private fun String.trimUnescapedTrailingSpaces(): String {
    var end = length
    while (end > 0 && this[end - 1] == ' ') {
        var escapes = 0
        var index = end - 2
        while (index >= 0 && this[index] == '\\') {
            escapes += 1
            index -= 1
        }
        if (escapes % 2 == 1) break
        end -= 1
    }
    return substring(0, end)
}

private fun String.toGitIgnoreRegex(): String = buildString {
    var index = 0
    while (index < this@toGitIgnoreRegex.length) {
        when (val character = this@toGitIgnoreRegex[index]) {
            '\\' -> {
                val literal = this@toGitIgnoreRegex.getOrNull(index + 1) ?: '\\'
                append(Pattern.quote(literal.toString()))
                index += if (index + 1 < this@toGitIgnoreRegex.length) 2 else 1
            }
            '*' -> {
                if (this@toGitIgnoreRegex.getOrNull(index + 1) == '*') {
                    if (this@toGitIgnoreRegex.getOrNull(index + 2) == '/') {
                        append("(?:.*/)?")
                        index += 3
                    } else {
                        append(".*")
                        index += 2
                    }
                } else {
                    append("[^/]*")
                    index += 1
                }
            }
            '?' -> {
                append("[^/]")
                index += 1
            }
            '[' -> {
                val closing = this@toGitIgnoreRegex.indexOf(']', startIndex = index + 1)
                if (closing < 0) {
                    append("\\[")
                    index += 1
                } else {
                    val content = this@toGitIgnoreRegex.substring(index + 1, closing)
                    append('[')
                    if (content.startsWith("!")) append('^')
                    append(content.removePrefix("!").replace("\\", "\\\\"))
                    append(']')
                    index = closing + 1
                }
            }
            else -> {
                if (character in ".(){}+^$|") append('\\')
                append(character)
                index += 1
            }
        }
    }
}
