package io.github.amichne.kast.cli.publicshell

import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicCliMissingMetadataTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `missing packaged schema produces one explicit failure in a real child`() {
        val stdout = temporary.resolve("stdout.json")
        val stderr = temporary.resolve("stderr.txt")
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Duser.home=$temporary",
                    "-cp",
                    System.getProperty("java.class.path"),
                    MissingPublicMetadataChild::class.java.name,
                )
                .directory(temporary.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply { environment().clear() }
                .start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Metadata failure waited for stdin")
            assertEquals(1, process.exitValue(), Files.readString(stderr))
            assertTrue(Files.readString(stderr).contains("missing packaged query schema"))
            val text = Files.readString(stdout)
            assertEquals("rejected", Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(1, text.count { it == '\n' })
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}

/** A fresh child loader omits one resource while every production class keeps its real implementation. */
object MissingPublicMetadataChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val urls = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        val loader =
            object : URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()) {
                override fun getResourceAsStream(name: String): InputStream? {
                    if (name == "io/github/amichne/kast/appserver/query/query_symbols.parameters.json") {
                        System.err.println("missing packaged query schema")
                        return null
                    }
                    return super.getResourceAsStream(name)
                }
            }
        Thread.currentThread().contextClassLoader = loader
        loader.use {
            it.loadClass("io.github.amichne.kast.cli.publicshell.KastPublicCliMain")
                .getMethod("main", Array<String>::class.java)
                .invoke(null, arrayOf("describe", "query", "--section", "input"))
        }
    }
}
