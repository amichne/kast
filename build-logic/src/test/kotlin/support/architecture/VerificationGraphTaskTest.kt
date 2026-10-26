package support.architecture

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VerificationGraphTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `verification graph keeps test fixture consumers and rejects unrelated consumers`() {
        fixture()
        val result = runner().build()
        val report = report()
        val consumers = report.getValue("consumers").jsonArray.associate { row ->
            val entry = row.jsonObject
            entry.getValue("owner").jsonPrimitive.content to
                entry.getValue("affected").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(
            listOf(":query:contract", ":source:contract", ":symbol:contract"),
            consumers.getValue(":symbol:contract"),
        )
        assertEquals(listOf(":source:contract"), consumers.getValue(":source:contract"))
        val symbolRoots = report.getValue("consumers").jsonArray.single {
            it.jsonObject.getValue("owner").jsonPrimitive.content == ":symbol:contract"
        }.jsonObject.getValue("sourceRoots").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("main", "test", "testFixtures").flatMap { sourceSet ->
                listOf("java", "resources").map { "symbol/contract/src/$sourceSet/$it" }
            },
            symbolRoots,
        )
        assertEquals("DECLARED_PRODUCTION_TEST_FIXTURE_CONSUMERS", report.getValue("evidence").jsonPrimitive.content)
        assertFalse(result.tasks.any { it.path.endsWith("compileJava") || it.path == ":verifyKastArchitecture" })
        val repeated = runner().build()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(report, report())
        val build = directory.resolve("build.gradle")
        build.writeText(build.readText() + "\n// authored graph input changed\n")
        runner().build()
        assertFalse(report.getValue("inputs") == report().getValue("inputs"))
    }

    private fun fixture() {
        directory.resolve("settings.gradle").writeText(
            "rootProject.name = 'verification-graph-fixture'\n" +
                "include ':kernel', ':symbol:contract', ':query:contract', ':source:contract'\n",
        )
        directory.resolve("build.gradle").writeText(
            """
            plugins { id 'kast.architecture' }
            [':kernel', ':symbol:contract', ':query:contract', ':source:contract'].each {
                project(it) { apply plugin: 'java-library'; apply plugin: 'java-test-fixtures' }
            }
            project(':symbol:contract') { dependencies { api project(':kernel') } }
            project(':query:contract') { dependencies { testImplementation testFixtures(project(':symbol:contract')) } }
            project(':source:contract') { dependencies { testImplementation project(':query:contract') } }
            """.trimIndent(),
        )
        listOf("kernel", "symbol/contract", "query/contract", "source/contract").forEach {
            directory.resolve(it).createDirectories()
        }
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(directory.toFile())
        .withPluginClasspath()
        .withArguments("generateVerificationGraph", "--configuration-cache", "--console=plain", "--no-watch-fs")

    private fun report() = Json.parseToJsonElement(
        directory.resolve("build/reports/ci/verification-graph.json").readText(),
    ).jsonObject
}
