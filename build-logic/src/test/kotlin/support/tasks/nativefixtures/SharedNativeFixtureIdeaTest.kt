package support.tasks.nativefixtures

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SharedNativeFixtureIdeaTest {
    @TempDir lateinit var root: Path

    @Test
    fun `two consumers provision once and retain archive invalidation and output repair`() {
        fixture()
        val original = mapOf("lib/platform.jar" to "platform", "obsolete.jar" to "remove me")
        archive(original)
        val seed = run()
        assertEquals(TaskOutcome.SUCCESS, seed.task(PRODUCER)?.outcome)
        assertEquals(1, seed.tasks.count { it.path.endsWith(":extractNativeTestIdea") })
        assertHomes(original)

        root.resolve("symbol/intellij/src/main/kotlin/Unrelated.kt").apply {
            parent.createDirectories()
            writeText("class Unrelated")
        }
        val unchanged = run()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(PRODUCER)?.outcome)
        assertTrue(unchanged.output.contains("Configuration cache entry reused."))

        val changed = mapOf("lib/platform.jar" to "changed platform", "plugins/Kotlin/lib/plugin.jar" to "Kotlin")
        archive(changed)
        assertEquals(TaskOutcome.SUCCESS, run().task(PRODUCER)?.outcome)
        assertHomes(changed)

        root.resolve("relation/intellij/build/native-test/idea/lib/platform.jar").writeText("corrupted output")
        assertEquals(TaskOutcome.SUCCESS, run().task(PRODUCER)?.outcome)
        assertHomes(changed)

        GradleRunner.create().withProjectDir(root.toFile()).withArguments(
            ":relation:intellij:clean", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2",
        ).build()
        assertEquals(TaskOutcome.SUCCESS, run().task(PRODUCER)?.outcome)
        assertHomes(changed)
    }

    @Test
    fun `explicit home avoids the producer and changes there invalidate consumers`() {
        fixture()
        root.resolve("idea.zip").writeText("invalid archive must remain unobserved with an explicit home")
        val supplied = root.resolve("supplied").createDirectories()
        supplied.resolve("supplied.jar").writeText("external SDK")
        val property = "-PnativeFixtureIdeaHome=$supplied"
        assertNull(run(property).task(PRODUCER))
        assertHomes(mapOf("supplied.jar" to "external SDK"))
        supplied.resolve("supplied.jar").writeText("updated external SDK")
        val changed = run(property)
        assertNull(changed.task(PRODUCER))
        assertEquals(TaskOutcome.SUCCESS, changed.task(":symbol:intellij:observe")?.outcome)
        assertHomes(mapOf("supplied.jar" to "updated external SDK"))

        val rejected = runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, rejected.task(PRODUCER)?.outcome)
    }

    private fun fixture() {
        listOf("relation/intellij", "symbol/intellij", "runtime/hosted").forEach { root.resolve(it).createDirectories() }
        root.resolve("settings.gradle").writeText(
            "rootProject.name = 'shared-native-sdk'\ninclude ':relation:intellij', ':symbol:intellij', ':runtime:hosted'\n",
        )
        val classes = Path.of(SharedNativeFixtureIdea::class.java.protectionDomain.codeSource.location.toURI())
        val classpath = classes.toString().replace("\\", "\\\\").replace("'", "\\'")
        root.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files('$classpath') } }
            project(':relation:intellij') {
                apply plugin: 'base'
                tasks.register('extractNativeTestIdea', Sync) {
                    from({ zipTree(rootProject.file('idea.zip')) })
                    into(layout.buildDirectory.dir('native-test/idea'))
                }
            }
            configure([project(':symbol:intellij'), project(':runtime:hosted')]) {
                def sdk = support.tasks.nativefixtures.SharedNativeFixtureIdea.INSTANCE.select(project)
                tasks.register('observe', Sync) {
                    from(sdk.contents)
                    into(layout.buildDirectory.dir('observed'))
                }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.toFile())
        .withTestKitDir(root.resolve("test-kit").toFile())
        .withArguments(
            listOf(":symbol:intellij:observe", ":runtime:hosted:observe", "--configuration-cache", "--build-cache",
                "--parallel", "--console=plain", "--max-workers=2",
                "-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2") + arguments,
        )

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun archive(entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(root.resolve("idea.zip"))).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun assertHomes(expected: Map<String, String>) {
        listOf("symbol/intellij", "runtime/hosted").forEach { consumer ->
            val home = root.resolve("$consumer/build/observed")
            val observed = Files.walk(home).use { paths ->
                paths.filter(Files::isRegularFile).toList().associate { path ->
                    home.relativize(path).toString().replace('\\', '/') to path.readText()
                }
            }
            assertEquals(expected, observed)
        }
    }

    private companion object {
        const val PRODUCER = ":relation:intellij:extractNativeTestIdea"
    }
}
