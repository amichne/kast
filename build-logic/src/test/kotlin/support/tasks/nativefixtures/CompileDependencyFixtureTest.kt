package support.tasks.nativefixtures

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompileDependencyFixtureTest {
    @TempDir lateinit var root: Path

    @Test
    fun `one compiler invocation serves two independent consumers and unchanged inputs`() {
        fixture()
        val first = runner().build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileFixture")?.outcome)
        assertEquals(1, first.output.lineSequence().count { it.startsWith("Dependency fixture compiler invocation:") })
        val archive = root.resolve("build/fixture/effects.jar").readBytes()
        ZipFile(root.resolve("build/fixture/effects.jar").toFile()).use {
            assertTrue(it.getEntry("fixture/DependencyKt.class") != null)
        }
        assertArrayEquals(archive, root.resolve("build/first/effects.jar").readBytes())
        assertArrayEquals(archive, root.resolve("build/second/effects.jar").readBytes())
        Files.delete(root.resolve("build/first/effects.jar"))
        assertArrayEquals(archive, root.resolve("build/second/effects.jar").readBytes())
        val unchanged = runner().build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":compileFixture")?.outcome)
        assertTrue(unchanged.output.contains("Configuration cache entry reused."))
        assertFalse(unchanged.output.contains("Dependency fixture compiler invocation:"))
        assertArrayEquals(archive, root.resolve("build/first/effects.jar").readBytes())
    }

    @Test
    fun `same filename source compiler dependencies options and JDK identity invalidate output`() {
        fixture()
        runner().build()
        root.resolve("Dependency.kt").writeText("package fixture\nfun answer(): Int = 43\n")
        assertCompiled("source contents")
        marker("compiler-marker.jar", "compiler changed")
        assertCompiled("compiler classpath archive")
        marker("dependency-marker.jar", "dependency changed")
        assertCompiled("dependency classpath archive")
        assertCompiled("compiler arguments", "-PextraOption=-java-parameters")
        root.resolve("jdk-release").writeText("different toolchain identity")
        assertCompiled("JDK release identity", "-PextraOption=-java-parameters")
    }

    @Test
    fun `failed compilation removes the previous archive and never reaches consumers`() {
        fixture()
        runner().build()
        root.resolve("Dependency.kt").writeText("not valid Kotlin")
        val failed = runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":compileFixture")?.outcome)
        assertFalse(Files.exists(root.resolve("build/fixture/effects.jar")))
        assertFalse(Files.exists(root.resolve("build/tmp/compileFixture/effects.jar")))
        assertTrue(failed.tasks.none { it.path == ":first" || it.path == ":second" })
    }

    @Test
    fun `different authored copies reject before compilation`() {
        fixture()
        root.resolve("copy.kt").writeText("package fixture\nfun answer(): Int = 0\n")
        val failed = runner("-Pequivalent=copy.kt").buildAndFail()
        assertTrue(failed.output.contains("Dependency fixture sources differ"))
        assertFalse(failed.output.contains("Dependency fixture compiler invocation:"))
        assertFalse(Files.exists(root.resolve("build/fixture/effects.jar")))
    }

    private fun assertCompiled(changedInput: String, vararg arguments: String) {
        val result = runner(*arguments).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileFixture")?.outcome) {
            "compileFixture after $changedInput:\n${result.output.takeLast(8_192)}"
        }
    }

    private fun fixture() {
        val classes = Path.of(CompileDependencyFixture::class.java.protectionDomain.codeSource.location.toURI())
        val compiler = System.getProperty("kast.jsonContractParserClasspath").split(File.pathSeparator)
        val stdlib = compiler.single { Path.of(it).fileName.toString().startsWith("kotlin-stdlib-") }
        root.resolve("settings.gradle").writeText("rootProject.name = 'dependency-fixture'\n")
        root.resolve("Dependency.kt").writeText("package fixture\nfun answer(): Int = 42\n")
        root.resolve("jdk-release").writeText("initial toolchain identity")
        marker("compiler-marker.jar", "initial compiler")
        marker("dependency-marker.jar", "initial dependency")
        root.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files(${quote(classes.toString())}) } }
            apply plugin: 'java-base'
            def compiled = tasks.register('compileFixture', support.tasks.nativefixtures.CompileDependencyFixture) {
                sourceFile.set(layout.projectDirectory.file('Dependency.kt'))
                if (providers.gradleProperty('equivalent').isPresent()) {
                    equivalentSources.from(providers.gradleProperty('equivalent'))
                }
                compilerClasspath.from(${compiler.joinToString(",", transform = ::quote)}, 'compiler-marker.jar')
                dependencies.from(${quote(stdlib)}, 'dependency-marker.jar')
                compilerArguments.addAll(providers.gradleProperty('extraOption').map { [it] }.orElse([]))
                javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
                jdkRelease.set(layout.projectDirectory.file('jdk-release'))
                archiveFile.set(layout.buildDirectory.file('fixture/effects.jar'))
            }
            ['first', 'second'].each { name ->
                tasks.register(name, Copy) {
                    from(compiled.flatMap { it.archiveFile })
                    into(layout.buildDirectory.dir(name))
                }
            }
            """.trimIndent(),
        )
    }

    private fun marker(name: String, contents: String) {
        root.createDirectories()
        ZipOutputStream(Files.newOutputStream(root.resolve(name))).use {
            it.putNextEntry(ZipEntry("identity.txt"))
            it.write(contents.toByteArray())
            it.closeEntry()
        }
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.toFile())
        // These cases prove declared inputs without asynchronous OS watcher delivery.
        .withArguments(listOf("first", "second", "--configuration-cache", "--no-watch-fs", "--console=plain", "--max-workers=2",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2") + arguments)

    private fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
