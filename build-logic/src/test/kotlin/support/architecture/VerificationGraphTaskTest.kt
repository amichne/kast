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
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VerificationGraphTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `isolated consumer keeps the producer and its relocated output`() {
        fixture()
        val module = directory.resolve("kernel/build.gradle")
        module.writeText(module.readText() + """

            layout.buildDirectory = layout.projectDirectory.dir('relocated')
            def message = tasks.register('produceMessage', Copy) {
                from('message.txt')
                into(layout.buildDirectory.dir('message'))
            }
            configurations.consumable('messageElements') {
                outgoing.artifact(message.map { it.destinationDir })
            }
        """.trimIndent())
        directory.resolve("kernel/message.txt").writeText("producer output\n")
        val root = directory.resolve("build.gradle")
        root.writeText(root.readText() + """

            def message = support.ProjectOutputKt.consumeProjectOutput(project, 'messageFiles', ':kernel', 'messageElements')
            tasks.register('consumeMessage', Sync) {
                from(message)
                into(layout.buildDirectory.dir('message'))
            }
        """.trimIndent())
        fun consume() = runner().withArguments("consumeMessage", "--isolated-projects", "--console=plain").build()
        val result = consume()
        assertEquals(TaskOutcome.SUCCESS, result.task(":kernel:produceMessage")?.outcome)
        assertEquals("producer output\n", directory.resolve("build/message/message.txt").readText())
        assertTrue(java.nio.file.Files.exists(directory.resolve("kernel/relocated/message/message.txt")))
        val repeated = consume()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":consumeMessage")?.outcome)
    }

    @Test
    fun `help avoids module facts and unrelated task realization`() {
        fixture()
        val module = directory.resolve("symbol/contract/build.gradle")
        module.writeText(module.readText() + "\ntasks.register('unrelated') { throw new GradleException('eager task') }\n")
        val result = runner().withArguments("help", "--isolated-projects", "--console=plain").build()
        assertFalse(result.tasks.any { it.path.endsWith(":collectModuleBuildFacts") })
        assertFalse(result.tasks.any { it.path.endsWith(":compileJava") })
        assertFalse(java.nio.file.Files.exists(directory.resolve("build/reports/ci/verification-graph.json")))
    }

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
                "include ':kernel', ':symbol:contract', ':query:contract', ':source:contract'\n" +
                "gradle.lifecycle.beforeProject { group = 'example'; version = '1' }\n",
        )
        directory.resolve("build.gradle").writeText("plugins { id 'kast.architecture' }\n")
        val scripts = mapOf(
            "kernel" to "",
            "symbol/contract" to "dependencies { api project(':kernel') }",
            "query/contract" to "dependencies { testImplementation testFixtures(project(':symbol:contract')) }",
            "source/contract" to "dependencies { testImplementation project(':query:contract') }",
        )
        scripts.forEach { (path, dependencies) ->
            val module = directory.resolve(path).createDirectories()
            module.resolve("build.gradle").writeText(
                "plugins { id 'java-library'; id 'java-test-fixtures'; id 'kast.module-facts' }\n" + dependencies,
            )
        }
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(directory.toFile())
        .withPluginClasspath()
        .withArguments("generateVerificationGraph", "--isolated-projects", "--configuration-cache", "--console=plain", "--no-watch-fs")

    private fun report() = Json.parseToJsonElement(
        directory.resolve("build/reports/ci/verification-graph.json").readText(),
    ).jsonObject
}
