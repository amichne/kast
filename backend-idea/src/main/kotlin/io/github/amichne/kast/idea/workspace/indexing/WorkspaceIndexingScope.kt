package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.IndexingConfig
import io.github.amichne.kast.api.client.WorkspacePathPolicy
import io.github.amichne.kast.api.client.WorkspaceRelativePath
import io.github.amichne.kast.api.client.fields.WorkspaceIgnorePattern
import io.github.amichne.kast.api.client.fields.WorkspaceIndexingPattern
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path

internal data class WorkspaceIndexingScope(
    val includedPaths: List<WorkspaceSourcePath>,
    val ignoredPaths: List<Path>,
    val criticalPaths: List<WorkspaceSourcePath>,
    val unmatchedCriticalPatterns: List<WorkspaceIndexingPattern>,
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
    val root: NormalizedPath,
    val ignoreRules: List<IgnoreRule>,
    val criticalRules: List<WorkspaceIndexingPattern>,
) {
    fun resolve(candidates: Collection<Path>): WorkspaceIndexingScope {
        val rootPath = root.toJavaPath()
        val sourceFilePolicy = SourceIndexFilePolicy.forWorkspace(rootPath)
        val matchedCriticalPatterns = linkedSetOf<WorkspaceIndexingPattern>()
        val included = mutableListOf<WorkspaceSourcePath>()
        val ignored = mutableListOf<Path>()
        val critical = mutableListOf<WorkspaceSourcePath>()

        candidates
            .asSequence()
            .map { path -> if (path.isAbsolute) path else rootPath.resolve(path) }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .sortedBy { path -> path.toString().replace('\\', '/') }
            .forEach { path ->
                val relative = WorkspaceRelativePath.resolve(rootPath, path)
                if (relative == null) {
                    ignored.add(path)
                    return@forEach
                }
                val matchingCriticalPatterns = criticalRules
                    .filter { pattern -> pattern.matches(relative) }
                matchedCriticalPatterns += matchingCriticalPatterns

                val hardExcluded = WorkspacePathPolicy.isHardExcluded(relative)
                val ignoredByRule = ignoreRules.fold(false) { isIgnored, rule ->
                    if (rule.matches(relative)) !rule.negated else isIgnored
                }
                if ((hardExcluded || ignoredByRule) && matchingCriticalPatterns.isNotEmpty()) {
                    throw IndexingScopeConfigurationException.conflict(
                        relative,
                        matchingCriticalPatterns,
                    )
                }
                if (hardExcluded || ignoredByRule) {
                    ignored.add(path)
                } else {
                    val sourcePath = checkNotNull(sourceFilePolicy.sourcePath(relative)) {
                        "Included indexing path is not an eligible Kotlin source file: ${relative.value}"
                    }
                    included.add(sourcePath)
                    if (matchingCriticalPatterns.isNotEmpty()) critical.add(sourcePath)
                }
            }

        return WorkspaceIndexingScope(
            includedPaths = included,
            ignoredPaths = ignored,
            criticalPaths = critical,
            unmatchedCriticalPatterns = criticalRules
                .filterNot(matchedCriticalPatterns::contains),
        )
    }

    companion object {
        fun load(workspaceRoot: Path, config: IndexingConfig): WorkspaceIndexingRules {
            val root = NormalizedPath.of(workspaceRoot)
            return WorkspaceIndexingRules(
                root = root,
                ignoreRules = readKastIgnore(root.toJavaPath()) + config.ignoredPaths.value.map { pattern ->
                    IgnoreRule(
                        negated = false,
                        pattern = ConfiguredIgnorePattern(pattern),
                    )
                },
                criticalRules = config.criticalPaths.value.distinct(),
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
            path: WorkspaceRelativePath,
            criticalPatterns: Collection<WorkspaceIndexingPattern>,
        ): IndexingScopeConfigurationException = IndexingScopeConfigurationException(
            code = "INDEXING_SCOPE_CONFLICT",
            message = "Critical path ${path.value} is excluded by the persisted-index scope " +
                "(matched: ${criticalPatterns.joinToString()})",
        )
    }
}

private data class IgnoreRule(
    val negated: Boolean,
    private val pattern: IgnorePattern,
) {
    fun matches(relativePath: WorkspaceRelativePath): Boolean = pattern.matches(relativePath)
}

private sealed interface IgnorePattern {
    fun matches(relativePath: WorkspaceRelativePath): Boolean
}

private data class ConfiguredIgnorePattern(
    val pattern: WorkspaceIndexingPattern,
) : IgnorePattern {
    override fun matches(relativePath: WorkspaceRelativePath): Boolean = pattern.matches(relativePath)
}

private data class KastIgnorePattern(
    val pattern: WorkspaceIgnorePattern,
) : IgnorePattern {
    override fun matches(relativePath: WorkspaceRelativePath): Boolean = pattern.matches(relativePath)
}

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

private fun parseIgnoreRuleOrNull(rawPattern: String): IgnoreRule? {
    var pattern = rawPattern.removePrefix("\uFEFF").trimUnescapedTrailingSpaces()
    if (pattern.isEmpty() || pattern.startsWith("#")) return null
    val negated = pattern.startsWith("!")
    if (negated) pattern = pattern.drop(1)
    if (pattern.startsWith("\\#") || pattern.startsWith("\\!")) pattern = pattern.drop(1)
    if (pattern.isEmpty()) {
        throw IndexingScopeConfigurationException.invalid("Ignore pattern must not be empty")
    }
    val parsed = try {
        WorkspaceIgnorePattern.parseDirectiveBody(pattern)
    } catch (error: IllegalArgumentException) {
        throw IndexingScopeConfigurationException.invalid(error.message ?: "Invalid ignore pattern")
    }
    return IgnoreRule(negated = negated, pattern = KastIgnorePattern(parsed))
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
