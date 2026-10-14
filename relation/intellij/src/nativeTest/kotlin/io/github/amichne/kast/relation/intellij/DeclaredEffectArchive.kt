package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** A case-owned archive contains real compiler metadata and no attached source roots. */
internal class DeclaredEffectArchive private constructor(val root: Path, val jar: Path) : AutoCloseable {
    val classDigest =
        ZipFile(jar.toFile()).use { zip ->
            digest(
                zip.getInputStream(checkNotNull(zip.getEntry("effects/DependencyEffectsKt.class"))).use {
                    it.readBytes()
                }
            )
        }

    fun compileConsumers() {
        val callers = copyResource("CallerCombinations.kt")
        val targets = copyResource("Targets.kt")
        compile(listOf(callers, targets), root.resolve("consumer.jar"), listOf(jar))
    }

    private fun copyResource(name: String): Path = root.resolve(name).also { file -> Files.write(file, resource(name)) }

    override fun close() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    companion object {
        fun compile(): DeclaredEffectArchive {
            val root = Files.createTempDirectory("kast-declared-effect-case-")
            val source = root.resolve("DependencyEffects.kt")
            val jar = root.resolve("effects.jar")
            var transferred = false
            try {
                Files.write(source, resource("DependencyEffects.kt"))
                compile(listOf(source), jar, emptyList())
                val archive = DeclaredEffectArchive(root, jar)
                transferred = true
                return archive
            } finally {
                if (!transferred) {
                    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }

        fun resource(name: String): ByteArray =
            checkNotNull(DeclaredEffectArchive::class.java.getResourceAsStream("/semantic-effects/$name")) {
                    "Missing authored R1 fixture $name"
                }
                .use { it.readBytes() }

        private fun compile(sources: List<Path>, output: Path, dependencies: List<Path>) {
            val compiler = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib")
            val stdlib = compiler.resolve("kotlin-stdlib.jar")
            val transcript = output.resolveSibling(output.fileName.toString() + ".compiler.txt")
            val command =
                listOf(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    compiler.resolve("*").toString(),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib",
                    "-no-reflect",
                    "-classpath",
                    (dependencies + listOf(stdlib)).joinToString(java.io.File.pathSeparator),
                    "-d",
                    output.toString(),
                ) + sources.map(Path::toString)
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(transcript.toFile()).start()
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly().waitFor()
                error("R1 compiler setup exceeded its 60-second process limit")
            }
            val text = Files.newInputStream(transcript).use { it.readNBytes(65_536).toString(Charsets.UTF_8) }
            check(process.exitValue() == 0) { "R1 compiler setup failed with exit=${process.exitValue()}: $text" }
            println("R1 compilerJarSha256=${digest(Files.readAllBytes(compiler.resolve("kotlin-compiler.jar")))}")
        }

        private fun digest(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
