package io.github.amichne.kast.shared

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinFileIsolationTest {
    @Test
    fun `non-private top-level production types own same-named files`() {
        val productionRoots = listOf(
            Path.of("src/main/kotlin/io/github/amichne/kast/shared/hierarchy"),
            Path.of("src/main/kotlin/io/github/amichne/kast/shared/proofloss"),
        )

        productionRoots.forEach { root -> require(Files.isDirectory(root)) { "Missing production source root: $root" } }

        val violations = productionRoots.flatMap(::fileIsolationViolations)

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                prefix = "Top-level production types must own same-named files:\n",
                separator = "\n",
            ),
        )
    }

    private fun fileIsolationViolations(root: Path): List<String> =
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .sorted()
                .toList()
                .flatMap { file ->
                    TOP_LEVEL_TYPE.findAll(file.readText())
                        .map { match -> match.groupValues[2] }
                        .filter { typeName -> typeName != file.nameWithoutExtension }
                        .map { typeName -> "$file: $typeName belongs in $typeName.kt" }
                        .toList()
                }
        }

    private companion object {
        val TOP_LEVEL_TYPE = Regex(
            pattern = "^(?:(?:public|internal|protected|sealed|data|enum|annotation|value|fun|abstract|open|expect|actual)\\s+|@JvmInline\\s+)*(class|interface|object)\\s+([A-Za-z_][A-Za-z0-9_]*)",
            option = RegexOption.MULTILINE,
        )
    }
}
