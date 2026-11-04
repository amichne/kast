package io.github.amichne.kast.cli.publicshell

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue

internal class PublicCliProcessFixture(private val temporary: Path) {
    fun child(
        args: List<String>,
        bytes: ByteArray = byteArrayOf(),
        environment: Map<String, String> = mapOf("KAST_OUTPUT_TOON" to "0"),
        diagnostics: (String) -> Unit = {},
    ): Pair<Int, String> {
        val (process, stdout, stderr) = start(args, environment)
        try {
            process.outputStream.use { it.write(bytes) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), Files.readString(stderr))
            diagnostics(Files.readString(stderr))
            return process.exitValue() to Files.readString(stdout)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    fun start(
        args: List<String>,
        environment: Map<String, String> = mapOf("KAST_OUTPUT_TOON" to "0"),
    ): Triple<Process, Path, Path> {
        val stdout = Files.createTempFile(temporary, "stdout", ".json")
        val stderr = Files.createTempFile(temporary, "stderr", ".txt")
        val process =
            ProcessBuilder(
                    listOf(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Duser.home=$temporary",
                        "-cp",
                        System.getProperty("java.class.path"),
                        KastPublicCliMain::class.java.name,
                    ) + args
                )
                .directory(temporary.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }
                .start()
        return Triple(process, stdout, stderr)
    }
}
