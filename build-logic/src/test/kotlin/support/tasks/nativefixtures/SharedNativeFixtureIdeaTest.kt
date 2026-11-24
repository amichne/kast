package support.tasks.nativefixtures

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Product metadata whose fixed build field is absent. */
@Serializable
private data class ProductInfoWithoutBuild(val name: String)

class SharedNativeFixtureIdeaTest {
    @TempDir lateinit var root: Path

    @Test
    fun `two consumers provision once and retain archive invalidation and output repair`() {
        fixture()
        val original = mapOf("lib/platform.jar" to "platform", "obsolete.jar" to "remove me")
        archive(original)
        val seed = run()
        assertProvisioned(seed, "initial SDK archive")
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
        assertProvisioned(run(), "same-path SDK archive replacement")
        assertHomes(changed)

        root.resolve("relation/intellij/build/native-test/idea/lib/platform.jar").writeText("corrupted output")
        assertProvisioned(run(), "SDK output corruption")
        assertHomes(changed)

        GradleRunner.create().withProjectDir(root.toFile()).withArguments(
            ":relation:intellij:clean", "--no-watch-fs", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2",
        ).build()
        assertProvisioned(run(), "producer clean")
        assertHomes(changed)
    }

    @Test
    fun `explicit home avoids the producer and changes there invalidate consumers`() {
        fixture()
        root.resolve("idea.zip").writeText("invalid archive must remain unobserved with an explicit home")
        val supplied = root.resolve("supplied").createDirectories()
        supplied.resolve("supplied.jar").writeText("external SDK")
        supplied.resolve(PRODUCT_INFO).writeText(productInfo(PINNED))
        val property = "-PnativeFixtureIdeaHome=$supplied"
        val admitted = run(property)
        assertNull(admitted.task(PRODUCER))
        assertEquals(TaskOutcome.SUCCESS, admitted.task(":symbol:intellij:$ADMISSION")?.outcome)
        assertHomes(mapOf("supplied.jar" to "external SDK", PRODUCT_INFO to productInfo(PINNED)))
        supplied.resolve("supplied.jar").writeText("updated external SDK")
        val changed = run(property)
        assertNull(changed.task(PRODUCER))
        assertEquals(TaskOutcome.SUCCESS, changed.task(":symbol:intellij:observe")?.outcome)
        assertHomes(mapOf("supplied.jar" to "updated external SDK", PRODUCT_INFO to productInfo(PINNED)))

        val rejected = runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, rejected.task(PRODUCER)?.outcome)
    }

    @Test
    fun `missing supplied home fails admission before any consumer`() {
        fixture()
        val supplied = root.resolve("removed-sdk")
        assertRejected(supplied, "the directory does not exist")
    }

    @Test
    fun `supplied home without product info fails admission before any consumer`() {
        fixture()
        val supplied = root.resolve("bare-sdk").createDirectories()
        supplied.resolve("supplied.jar").writeText("external SDK")
        assertRejected(supplied, "the directory has no product-info.json")
    }

    @Test
    fun `supplied home of another build fails admission before any consumer`() {
        fixture()
        val supplied = root.resolve("other-sdk").createDirectories()
        supplied.resolve(PRODUCT_INFO).writeText(productInfo("261.1.1"))
        assertRejected(supplied, "it is build 261.1.1 instead of pinned build $PINNED")
    }

    @Test
    fun `supplied home with malformed product info fails admission before any consumer`() {
        fixture()
        val supplied = root.resolve("malformed-sdk").createDirectories()
        supplied.resolve(PRODUCT_INFO).writeText(Json.encodeToString(ProductInfoWithoutBuild("IntelliJ IDEA")))
        assertRejected(supplied, "its product-info.json has no valid buildNumber")
    }

    @Test
    fun `selected jars keep path order across archives and follow the extraction`() {
        fixture()
        val names = listOf("lib/z.jar", "lib/a.jar", "lib/nested/m.jar", "plugins/Kotlin/lib/k.jar")
        val other = mapOf("lib/notes.txt" to "text", "plugins/Kotlin/lib/jps/skipped.jar" to "jps", "bin/tool.jar" to "bin")
        val expected = listOf("lib/a.jar", "lib/nested/m.jar", "lib/z.jar", "plugins/Kotlin/lib/k.jar")
        for (order in listOf(names, names.reversed())) {
            archive(order.associateWith { it } + other)
            GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                ":relation:intellij:clean", "--no-watch-fs", "-Dorg.gradle.jvmargs=-Xmx512m",
            ).build()
            // The first pass extracts the distribution after the configuration cache stored its task graph.
            repeat(2) {
                val listed = listJars()
                assertEquals(expected, listed)
            }
        }
        archive(mapOf("lib/b.jar" to "only"))
        assertEquals(listOf("lib/b.jar"), listJars())
    }

    @Test
    fun `a synchronization before the extraction resolves no jars and does not fail`() {
        fixture()
        archive(mapOf("lib/a.jar" to "a"))
        val synchronized =
            GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                "-Psynchronize=true", "help", "--no-watch-fs", "-Dorg.gradle.jvmargs=-Xmx512m",
            ).build()
        assertTrue(synchronized.output.contains("synchronized jars: []")) { synchronized.output.takeLast(4_096) }
        assertNull(synchronized.task(PRODUCER))
        assertEquals(listOf("lib/a.jar"), listJars())
    }

    @Test
    fun `patterns match names as file trees do`() {
        assertTrue(antPattern("lib/**/*.jar").matches("lib/a.jar"))
        assertTrue(antPattern("lib/**/*.jar").matches("lib/a/b/c.jar"))
        assertTrue(antPattern("plugins/Kotlin/lib/jps/**").matches("plugins/Kotlin/lib/jps/a/b.jar"))
        assertTrue(antPattern("lib/intellij.libraries.ktor.*.jar").matches("lib/intellij.libraries.ktor.utils.jar"))
        assertTrue(!antPattern("lib/*.jar").matches("lib/nested/a.jar"))
        assertTrue(!antPattern("lib/**/*.jar").matches("plugins/lib/a.jar"))
        assertTrue(!antPattern("lib/a.jar").matches("lib/aXjar"))
    }

    private fun listJars(): List<String> {
        val result =
            GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                ":symbol:intellij:listJars", "--configuration-cache", "--no-watch-fs", "--console=plain",
                "-Dorg.gradle.jvmargs=-Xmx512m",
            ).build()
        assertTrue(result.task(PRODUCER) != null) { result.output.takeLast(4_096) }
        return root.resolve("symbol/intellij/build/jars.txt").readText().lines().filter { it.isNotEmpty() }
    }

    private fun assertRejected(supplied: Path, problem: String) {
        root.resolve("idea.zip").writeText("invalid archive must remain unobserved with an explicit home")
        val rejected = runner("-PnativeFixtureIdeaHome=$supplied").buildAndFail()
        val expected = "Native fixture IDEA home rejected: Gradle property nativeFixtureIdeaHome points at $supplied, " +
            "but $problem. Remove the nativeFixtureIdeaHome override to use the extracted pinned distribution, " +
            "or point it at an IntelliJ IDEA $PINNED distribution."
        assertTrue(rejected.output.contains(expected)) { rejected.output.takeLast(8_192) }
        val admissions = rejected.tasks.filter { it.path.endsWith(":$ADMISSION") }
        assertTrue(admissions.isNotEmpty() && admissions.all { it.outcome == TaskOutcome.FAILED }) {
            rejected.output.takeLast(8_192)
        }
        assertNull(rejected.task(PRODUCER))
        assertTrue(rejected.tasks.none { it.path.endsWith(":observe") }) { rejected.output.takeLast(8_192) }
        listOf("symbol/intellij", "runtime/hosted").forEach {
            assertTrue(!Files.exists(root.resolve("$it/build/observed")), "$it consumer ran")
        }
    }

    private fun productInfo(build: String): String = Json.encodeToString(NativeFixtureProductInfo(build, emptyList()))

    private fun fixture() {
        listOf("relation/intellij", "symbol/intellij", "runtime/hosted").forEach { root.resolve(it).createDirectories() }
        root.resolve("settings.gradle").writeText(
            "rootProject.name = 'shared-native-sdk'\ninclude ':relation:intellij', ':symbol:intellij', ':runtime:hosted'\n",
        )
        // Admission decodes product metadata, so the plugin classpath carries its serialization runtime.
        val classpath = listOf(SharedNativeFixtureIdea::class.java, Json::class.java, SerializationException::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct()
            .joinToString(", ") { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
        root.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files($classpath) } }
            project(':relation:intellij') {
                apply plugin: 'base'
                tasks.register('extractNativeTestIdea', Sync) {
                    from({ zipTree(rootProject.file('idea.zip')) })
                    into(layout.buildDirectory.dir('native-test/idea'))
                }
            }
            configure([project(':symbol:intellij'), project(':runtime:hosted')]) {
                def sdk = support.tasks.nativefixtures.SharedNativeFixtureIdea.INSTANCE.select(project, '$PINNED')
                tasks.register('observe', Sync) {
                    from(sdk.contents)
                    into(layout.buildDirectory.dir('observed'))
                }
                def jars = sdk.jars(['lib/**/*.jar', 'plugins/Kotlin/lib/**/*.jar'], ['plugins/Kotlin/lib/jps/**'])
                configurations { classpath }
                dependencies { classpath jars }
                if (providers.gradleProperty('synchronize').isPresent()) {
                    println "synchronized jars: " + configurations.classpath.files.collect { it.name }
                }
                def home = sdk.home.get().asFile.toPath()
                def output = layout.buildDirectory.file('jars.txt')
                def resolved = files(configurations.classpath)
                tasks.register('listJars') {
                    inputs.files(resolved)
                    outputs.file(output)
                    doLast {
                        output.get().asFile.text = resolved.files.collect { home.relativize(it.toPath()).toString() }.join('\n')
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.toFile())
        .withArguments(
            // These cases prove declared inputs without asynchronous OS watcher delivery.
            listOf(":symbol:intellij:observe", ":runtime:hosted:observe", "--configuration-cache", "--no-watch-fs", "--build-cache",
                "--parallel", "--console=plain", "--max-workers=2",
                "-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2") + arguments,
        )

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun assertProvisioned(result: BuildResult, changedInput: String) {
        assertEquals(TaskOutcome.SUCCESS, result.task(PRODUCER)?.outcome) {
            "$PRODUCER after $changedInput:\n${result.output.takeLast(8_192)}"
        }
    }

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
        const val ADMISSION = "admitNativeFixtureIdea"
        const val PINNED = "262.9437.185"
        const val PRODUCT_INFO = "product-info.json"
    }
}
