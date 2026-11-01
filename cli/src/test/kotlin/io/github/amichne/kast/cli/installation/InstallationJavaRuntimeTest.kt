package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationJavaRuntimeTest {
    @Test
    fun `public Java rejection keeps its fixed document for incompatible and missing release metadata`(
        @TempDir temporary: Path
    ) {
        val home = runtime(temporary)
        for (release in listOf("JAVA_VERSION=\"24\"\n", "JAVA_VERSION=\"invalid\"\n", "")) {
            Files.writeString(home.value.resolve("release"), release)
            val result = launch(temporary, home, InstallationJavaFailureMode.PUBLIC_QUERY)
            assertEquals(4, result.first)
            val document = Json.parseToJsonElement(result.second).jsonObject
            assertEquals(setOf("type", "failure"), document.keys)
            assertEquals("rejected", document.getValue("type").jsonPrimitive.content)
            assertEquals("CATALOG_UNAVAILABLE", document.getValue("failure").jsonPrimitive.content)
            assertEquals(1, result.second.count { it == '\n' })
        }
    }

    @Test
    fun `public Java rejection reports a closed stdout pipe as output failure`(@TempDir temporary: Path) {
        val home = runtime(temporary)
        Files.delete(home.value.resolve("release"))
        val script =
            Files.writeString(
                temporary.resolve("closed-output.sh"),
                "read -r gate\n" + installationJavaRuntimeEnvironment(home, InstallationJavaFailureMode.PUBLIC_QUERY),
            )
        val child = ProcessBuilder("/bin/sh", script.toString()).start()
        try {
            child.inputStream.close()
            child.outputStream.use { it.write("ready\n".toByteArray()) }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals(5, child.exitValue(), child.errorReader().readText())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `installed launcher selects quoted JBR despite ambient Java`(@TempDir temporary: Path) {
        val home = runtime(temporary)
        val result = launch(temporary, home)
        assertEquals(0, result.first)
        assertEquals(
            listOf(home.value.toString(), home.value.resolve("bin/java").toString()),
            result.second.lines().filter(String::isNotBlank),
        )
    }

    @Test
    fun `installed launcher rejects missing or incompatible JBR before the child boundary`(@TempDir temporary: Path) {
        val home = runtime(temporary)
        for (release in listOf("JAVA_VERSION=\"24.0.2\"\n", "JAVA_VERSION=\"invalid\"\n", "")) {
            Files.writeString(home.value.resolve("release"), release)
            val result = launch(temporary, home)
            assertEquals(1, result.first)
            assertEquals("", result.second)
            assertTrue(result.third.contains("selected IDEA JBR requires Java 25"))
        }
        Files.delete(home.value.resolve("release"))
        assertEquals(1, launch(temporary, home).first)
    }

    @Test
    fun `ambiguous or incomplete JBR declarations reject before the child boundary`(@TempDir temporary: Path) {
        val home = runtime(temporary)
        for (release in
            listOf(
                "JAVA_VERSION=\"25.0.2\"\nJAVA_VERSION=\"26\"\n",
                "JAVA_VERSION=\"25\"\nJAVA_VERSION=\"25\"\n",
                "JAVA_VERSION=\"25.0.2\n",
                "JAVA_VERSION=\"25\"trailing\n",
                "JAVA_VERSION=\"25\"\nJAVA_VERSION=invalid\n",
                "JAVA_VERSION=invalid\nJAVA_VERSION=\"25\"\n",
            )) {
            Files.writeString(home.value.resolve("release"), release)
            val result = launch(temporary, home)
            assertEquals(1, result.first, release)
            assertEquals("", result.second, release)
            assertTrue(result.third.contains("selected IDEA JBR requires Java 25"), release)
        }
    }

    @Test
    fun `single compatible declaration admits the selected JBR with surrounding metadata`(@TempDir temporary: Path) {
        val home = runtime(temporary)
        for (version in listOf("25", "25.0.2", "26-ea")) {
            Files.writeString(
                home.value.resolve("release"),
                "OS_ARCH=\"aarch64\"\nJAVA_VERSION=\"$version\"\nIMPLEMENTOR=\"JetBrains\"\n",
            )
            val result = launch(temporary, home)
            assertEquals(0, result.first, version)
            assertEquals(
                listOf(home.value.toString(), home.value.resolve("bin/java").toString()),
                result.second.lines().filter(String::isNotBlank),
                version,
            )
        }
    }

    private fun runtime(root: Path): InstallationPath {
        val home = Files.createDirectories(root.resolve("IDEA's Home With Spaces/jbr/Contents/Home"))
        val java = Files.createDirectories(home.resolve("bin")).resolve("java")
        Files.writeString(java, "#!/bin/sh\nprintf '%s\\n' \"${'$'}@\"\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n")
        return (InstallationPath.parse(home.toString()) as Refinement.Refined).value
    }

    private fun launch(
        root: Path,
        home: InstallationPath,
        failureMode: InstallationJavaFailureMode = InstallationJavaFailureMode.PRIVATE_LAUNCHER,
    ): Triple<Int, String, String> {
        val script =
            Files.writeString(
                root.resolve("launch.sh"),
                installationJavaRuntimeEnvironment(home, failureMode) +
                    "\nexec \"${'$'}JAVA\" \"${'$'}JAVA_HOME\" \"${'$'}JAVA\"\n",
            )
        val process =
            ProcessBuilder("/bin/sh", script.toString())
                .apply {
                    environment()["JAVA_HOME"] = "/wrong/ambient/runtime"
                    environment()["JAVA"] = "/usr/bin/java"
                    environment()["PATH"] = "/usr/bin:/bin"
                }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        return Triple(process.waitFor(), output, error)
    }
}
