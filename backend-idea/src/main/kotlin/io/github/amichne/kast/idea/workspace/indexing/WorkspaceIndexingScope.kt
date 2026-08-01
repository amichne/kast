package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.IndexingConfig
import io.github.amichne.kast.api.client.WorkspacePathPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

internal data class WorkspaceIndexingScope(
    val includedPaths: List<Path>,
    val ignoredPaths: List<Path>,
    val criticalPaths: List<Path>,
    val unmatchedCriticalPatterns: List<String>,
) {
    companion object {
        fun resolve(
            workspaceRoot: Path,
            config: IndexingConfig,
            candidates: Collection<Path>,
        ): WorkspaceIndexingScope = WorkspaceIndexingRules.load(workspaceRoot, config).resolve(candidates)
    }
}

private data class WorkspaceIndexingRules(
    val root: Path,
    val ignoreRules: List<IgnoreRule>,
    val criticalRules: List<Pair<String, WorkspaceGlob>>,
) {
    fun resolve(candidates: Collection<Path>): WorkspaceIndexingScope {
        val matchedCriticalPatterns = linkedSetOf<String>()
        val included = mutableListOf<Path>()
        val ignored = mutableListOf<Path>()
        val critical = mutableListOf<Path>()

        candidates
            .asSequence()
            .map { path -> if (path.isAbsolute) path else root.resolve(path) }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .sortedBy { path -> path.toString().replace('\\', '/') }
            .forEach { path ->
                if (!path.startsWith(root)) {
                    ignored.add(path)
                    return@forEach
                }
                val relativePath = root.relativize(path)
                val relative = relativePath.toString().replace('\\', '/')
                val matchingCriticalPatterns = criticalRules
                    .filter { (_, rule) -> rule.matches(relative) }
                    .map(Pair<String, WorkspaceGlob>::first)
                matchedCriticalPatterns += matchingCriticalPatterns

                val hardExcluded = WorkspacePathPolicy.isHardExcluded(relativePath)
                val ignoredByRule = ignoreRules.fold(false) { isIgnored, rule ->
                    if (rule.glob.matches(relative)) !rule.negated else isIgnored
                }
                if ((hardExcluded || ignoredByRule) && matchingCriticalPatterns.isNotEmpty()) {
                    throw IndexingScopeConfigurationException.conflict(relative, matchingCriticalPatterns)
                }
                if (hardExcluded || ignoredByRule) {
                    ignored.add(path)
                } else {
                    included.add(path)
                    if (matchingCriticalPatterns.isNotEmpty()) critical.add(path)
                }
            }

        return WorkspaceIndexingScope(
            includedPaths = included,
            ignoredPaths = ignored,
            criticalPaths = critical,
            unmatchedCriticalPatterns = criticalRules
                .map(Pair<String, WorkspaceGlob>::first)
                .filterNot(matchedCriticalPatterns::contains),
        )
    }

    companion object {
        fun load(workspaceRoot: Path, config: IndexingConfig): WorkspaceIndexingRules {
            val root = workspaceRoot.toAbsolutePath().normalize()
            val criticalRules = config.criticalPaths.value.distinct().map { pattern ->
                if (pattern.startsWith("!")) {
                    throw IndexingScopeConfigurationException.invalid(
                        "indexing.criticalPaths does not support negation: $pattern",
                    )
                }
                pattern to WorkspaceGlob.parse(pattern)
            }
            return WorkspaceIndexingRules(
                root = root,
                ignoreRules = readKastIgnore(root) + config.ignoredPaths.value.map(::parseIgnoreRule),
                criticalRules = criticalRules,
            )
        }
    }
}

internal class WorkspaceIndexingScopeCache(
    private val reportFailure: (IndexingScopeConfigurationException) -> Unit = {},
) {
    private var lastValid: WorkspaceIndexingRules? = null

    @Synchronized
    fun resolve(
        workspaceRoot: Path,
        config: IndexingConfig,
        candidates: Collection<Path>,
    ): WorkspaceIndexingScope = try {
        WorkspaceIndexingRules.load(workspaceRoot, config).let { rules ->
            rules.resolve(candidates).also { lastValid = rules }
        }
    } catch (error: IndexingScopeConfigurationException) {
        reportFailure(error)
        lastValid?.resolve(candidates) ?: throw error
    }
}

internal class IndexingScopeConfigurationException private constructor(
    val code: String,
    message: String,
) : IllegalArgumentException(message) {
    companion object {
        fun invalid(message: String): IndexingScopeConfigurationException =
            IndexingScopeConfigurationException("INDEXING_SCOPE_INVALID", message)

        fun conflict(
            path: String,
            criticalPatterns: Collection<String>,
        ): IndexingScopeConfigurationException = IndexingScopeConfigurationException(
            code = "INDEXING_SCOPE_CONFLICT",
            message = "Critical path $path is excluded by the persisted-index scope " +
                "(matched: ${criticalPatterns.joinToString()})",
        )
    }
}

private data class IgnoreRule(
    val negated: Boolean,
    val glob: WorkspaceGlob,
)

private fun readKastIgnore(workspaceRoot: Path): List<IgnoreRule> {
    val path = workspaceRoot.resolve(".kastignore")
    if (!Files.exists(path)) return emptyList()
    return try {
        Files.readAllLines(path).mapNotNull(::parseIgnoreRuleOrNull)
    } catch (error: Exception) {
        if (error is IndexingScopeConfigurationException) throw error
        throw IndexingScopeConfigurationException.invalid("Cannot read $path: ${error.message}")
    }
}

private fun parseIgnoreRule(pattern: String): IgnoreRule =
    parseIgnoreRuleOrNull(pattern)
        ?: throw IndexingScopeConfigurationException.invalid("Ignore pattern must not be blank or a comment")

private fun parseIgnoreRuleOrNull(rawPattern: String): IgnoreRule? {
    var pattern = rawPattern.removePrefix("\uFEFF").trimUnescapedTrailingSpaces()
    if (pattern.isEmpty() || pattern.startsWith("#")) return null
    val negated = pattern.startsWith("!")
    if (negated) pattern = pattern.drop(1)
    if (pattern.startsWith("\\#") || pattern.startsWith("\\!")) pattern = pattern.drop(1)
    if (pattern.isEmpty()) {
        throw IndexingScopeConfigurationException.invalid("Ignore pattern must not be empty")
    }
    return IgnoreRule(negated = negated, glob = WorkspaceGlob.parse(pattern))
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

private class WorkspaceGlob private constructor(
    private val regex: Regex,
) {
    fun matches(relativePath: String): Boolean = regex.matches(relativePath)

    companion object {
        fun parse(rawPattern: String): WorkspaceGlob {
            var pattern = rawPattern.removePrefix("/")
            if (pattern.endsWith("/")) pattern = pattern.dropLast(1)
            if (pattern.isEmpty()) {
                throw IndexingScopeConfigurationException.invalid("Path pattern must not be empty")
            }
            val anchored = '/' in pattern
            val prefix = if (anchored) "^" else "^(?:.*/)?"
            return try {
                WorkspaceGlob(Regex(prefix + pattern.toGitIgnoreRegex() + "(?:/.*)?$"))
            } catch (error: IllegalArgumentException) {
                throw IndexingScopeConfigurationException.invalid(
                    "Invalid path pattern $rawPattern: ${error.message}",
                )
            }
        }
    }
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
