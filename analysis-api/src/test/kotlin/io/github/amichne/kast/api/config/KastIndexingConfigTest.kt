package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.ConfigurationDefault
import io.github.amichne.kast.api.client.fields.WorkspaceIndexingPattern
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastIndexingConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun serverMaxResultsExposesSectionKeyAndTypedDefault() {
        val maxResults = KastConfig.defaults().server.maxResults

        assertEquals("server", maxResults.section)
        assertEquals("maxResults", maxResults.key)
        assertEquals(ConfigurationDefault(500), maxResults.default)
        assertEquals(500, maxResults.value)
    }

    @Test
    fun relationshipParallelismExposesRaisedTypedDefault() {
        val parallelism = KastConfig.defaults().indexing.relationships.parallelism

        assertEquals("indexing.relationships", parallelism.section)
        assertEquals("parallelism", parallelism.key)
        assertEquals(ConfigurationDefault(4), parallelism.default)
        assertEquals(4, parallelism.value)
    }

    @Test
    fun relationshipModulePriorityDepthExposesTypedDefault() {
        val modulePriorityDepth = KastConfig.defaults().indexing.relationships.modulePriorityDepth

        assertEquals("indexing.relationships", modulePriorityDepth.section)
        assertEquals("modulePriorityDepth", modulePriorityDepth.key)
        assertEquals(ConfigurationDefault(2), modulePriorityDepth.default)
        assertEquals(2, modulePriorityDepth.value)
    }

    @Test
    fun `indexing scope and graph batch expose typed defaults`() {
        val indexing = KastConfig.defaults().indexing

        assertEquals(emptyList<WorkspaceIndexingPattern>(), indexing.criticalPaths.value)
        assertEquals(emptyList<WorkspaceIndexingPattern>(), indexing.ignoredPaths.value)
        assertEquals(32, indexing.graph.batchSize.value)
        assertEquals("indexing", indexing.criticalPaths.section)
        assertEquals("criticalPaths", indexing.criticalPaths.key)
        assertEquals("indexing.graph", indexing.graph.batchSize.section)
    }

    @Test
    fun `workspace indexing scope arrays and graph batch parse from toml`() {
        tempDir.resolve("config.toml").writeText(
            """
                [indexing]
                criticalPaths = ["src/main/**", "build.gradle.kts"]
                ignoredPaths = ["samples/**"]

                [indexing.graph]
                batchSize = 17
            """.trimIndent(),
        )

        val indexing = KastConfig.loadGlobal(configHome = { tempDir }).indexing

        assertEquals(
            listOf("src/main/**", "build.gradle.kts"),
            indexing.criticalPaths.value.map(WorkspaceIndexingPattern::toString),
        )
        assertEquals(listOf("samples/**"), indexing.ignoredPaths.value.map(WorkspaceIndexingPattern::toString))
        assertEquals(17, indexing.graph.batchSize.value)
    }

    @Test
    fun `workspace indexing scope accepts multiline toml arrays and quoted comments`() {
        tempDir.resolve("config.toml").writeText(
            """
                [indexing]
                criticalPaths = [
                  "src/main/**", # production sources
                  'literal#name.kt',
                ]
                ignoredPaths = [
                  "samples/**",
                  "generated/#literal.kt", # trailing comment
                ]
            """.trimIndent(),
        )

        val indexing = KastConfig.loadGlobal(configHome = { tempDir }).indexing

        assertEquals(
            listOf("src/main/**", "literal#name.kt"),
            indexing.criticalPaths.value.map(WorkspaceIndexingPattern::toString),
        )
        assertEquals(
            listOf("samples/**", "generated/#literal.kt"),
            indexing.ignoredPaths.value.map(WorkspaceIndexingPattern::toString),
        )
    }

    @Test
    fun `toml rejects invalid workspace indexing patterns at the config boundary`() {
        invalidWorkspaceIndexingPatterns.forEach { pattern ->
            listOf("criticalPaths", "ignoredPaths").forEach { field ->
                tempDir.resolve("config.toml").writeText(
                    """
                        [indexing]
                        $field = ["$pattern"]
                    """.trimIndent(),
                )

                assertThrows(IllegalArgumentException::class.java) {
                    KastConfig.loadGlobal(configHome = { tempDir })
                }
            }
        }
    }

    @Test
    fun `json rejects invalid workspace indexing patterns during deserialization`() {
        invalidWorkspaceIndexingPatterns.forEach { pattern ->
            listOf("criticalPaths", "ignoredPaths").forEach { field ->
                val runtimeConfig = tempDir.resolve("runtime-config.json").also { path ->
                    path.writeText(
                        """
                            {
                              "indexing": {
                                "$field": ["$pattern"]
                              }
                            }
                        """.trimIndent(),
                    )
                }

                assertThrows(IllegalArgumentException::class.java) {
                    KastConfig.loadResolvedJson(runtimeConfig)
                }
            }
        }
    }

    @Test
    fun `json rejects invalid graph batch size during deserialization`() {
        val runtimeConfig = tempDir.resolve("runtime-config.json").also { path ->
            path.writeText(
                """
                    {
                      "indexing": {
                        "graph": {
                          "batchSize": 0
                        }
                      }
                    }
                """.trimIndent(),
            )
        }

        assertThrows(SerializationException::class.java) {
            KastConfig.loadResolvedJson(runtimeConfig)
        }
    }

    @Test
    fun `json rejects invalid relationship indexing quantities during deserialization`() {
        val invalidValues = mapOf(
            "batchSize" to 0,
            "parallelism" to 0,
            "modulePriorityDepth" to -1,
        )

        invalidValues.forEach { (field, value) ->
            val runtimeConfig = tempDir.resolve("runtime-config.json").also { path ->
                path.writeText(
                    """
                        {
                          "indexing": {
                            "relationships": {
                              "$field": $value
                            }
                          }
                        }
                    """.trimIndent(),
                )
            }

            assertThrows(SerializationException::class.java) {
                KastConfig.loadResolvedJson(runtimeConfig)
            }
        }
    }

    @Test
    fun `toml rejects invalid relationship indexing quantities at the config boundary`() {
        val invalidValues = mapOf(
            "batchSize" to 0,
            "parallelism" to 0,
            "modulePriorityDepth" to -1,
        )

        invalidValues.forEach { (field, value) ->
            tempDir.resolve("config.toml").writeText(
                """
                    [indexing.relationships]
                    $field = $value
                """.trimIndent(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                KastConfig.loadGlobal(configHome = { tempDir })
            }
        }
    }

    private companion object {
        val invalidWorkspaceIndexingPatterns = listOf(
            " ",
            "#comment",
            "!generated/**",
            "../outside/**",
            "/Users/example/project/**",
            "[]",
        )
    }
}
