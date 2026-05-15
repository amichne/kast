package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ForbiddenEnvironmentTokenGuardTest {
    @Test
    fun removedEnvironmentTokensDoNotReappearInGuardedRepositorySurfaces() {
        val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
        val matches = repoRoot.guardedFiles()
            .flatMap { sourceFile -> sourceFile.findForbiddenTokenMatches(repoRoot) }
            .sortedWith(compareBy<TokenMatch> { it.relativePath }.thenBy { it.lineNumber }.thenBy { it.token })

        assertTrue(
            matches.isEmpty(),
            "Removed environment tokens must not reappear in guarded repository surfaces. Matches:\n" +
            matches.joinToString("\n") { match ->
                match.relativePath + ":" + match.lineNumber + ": " + match.token + ": " + match.line.trim()
            },
        )
    }

    private fun Path.guardedFiles(): List<Path> = Files.walk(this).use { stream ->
        stream
            .filter { candidate -> Files.isRegularFile(candidate) }
            .filter { candidate -> !candidate.relativeTo(this).hasExcludedSegment() }
            .filter { candidate -> candidate.relativeTo(this).isGuardedSurface() }
            .toList()
    }

    private fun Path.findForbiddenTokenMatches(repoRoot: Path): List<TokenMatch> {
        val relativePath = repoRoot.relativize(this).invariantSeparators()
        val lines = Files.readString(this).lines()
        return lines.flatMapIndexed { index, line ->
            forbiddenTokens.mapNotNull { token ->
                token.takeIf(line::contains)?.let {
                    TokenMatch(
                        relativePath = relativePath,
                        lineNumber = index + 1,
                        token = token,
                        line = line,
                    )
                }
            }
        }
    }

    private fun Path.relativeTo(root: Path): Path = root.relativize(this)

    private fun Path.hasExcludedSegment(): Boolean = (0 until nameCount)
        .any { index -> getName(index).toString() in excludedDirectoryNames }

    private fun Path.isGuardedSurface(): Boolean {
        val relativePath = invariantSeparators()
        return when {
            relativePath == "AGENTS.md" || relativePath.endsWith("/AGENTS.md") -> true
            relativePath.startsWith("docs/") && relativePath.endsWith(".md") -> true
            relativePath.startsWith(".github/hooks/") -> true
            relativePath.startsWith(".github/extensions/") -> true
            relativePath.endsWith(".sh") -> true
            relativePath.endsWith(".kts") -> true
            relativePath.endsWith(".kt") && ("/src/main/" in relativePath || "/src/test/" in relativePath) -> true
            else -> false
        }
    }

    private fun Path.invariantSeparators(): String = toString().replace(File.separatorChar, '/')

    private fun findRepoRoot(start: Path): Path = generateSequence(start.normalize()) { current -> current.parent }
                                                      .firstOrNull { candidate ->
                                                          Files.isRegularFile(
                                                              candidate.resolve(
                                                                  "kast-cli/build.gradle.kts"
                                                              )
                                                          )
                                                      }
                                                  ?: error("Could not locate repo root from " + start)

    private data class TokenMatch(
        val relativePath: String,
        val lineNumber: Int,
        val token: String,
        val line: String,
    )

    private companion object {
        val forbiddenTokens: List<String> = listOf(
            env("KAST", "HOME"),
            env("KAST", "INSTALL", "ROOT"),
            env("KAST", "BIN", "DIR"),
            env("KAST", "STANDALONE", "RUNTIME", "LIBS"),
            env("KAST", "CLI", "PATH"),
            env("KAST", "INTELLIJ", "DISABLE"),
            env("KAST", "STANDALONE", "SOCKET"),
            env("KAST", "INTELLIJ", "SOCKET"),
            env("KAST", "PARITY", "BROKEN", "FILE"),
            env("XDG", "CONFIG", "HOME"),
        )

        val excludedDirectoryNames: Set<String> = setOf(".git", ".gradle", "build", "site")

        fun env(vararg parts: String): String = parts.joinToString("_")
    }
}
