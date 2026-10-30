package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Actual candidate bytes in a private installation; no JVM entry or substituted invocation adapter. */
internal class NativePublicFixture(val evidence: Path) : AutoCloseable {
    // Unix-domain paths must fit the production 100-byte bound, independently of JUnit's temporary path.
    val home: Path = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kn -")
    val workspace: Path = Files.createDirectory(home.resolve("workspace with spaces"))
    val idea: Path = Files.createDirectory(home.resolve("inert-idea"))
    val installation: Path = Files.createDirectories(home.resolve(".local/share/kast/installation"))
    val payload: Path = installation.resolve("bin/kast-query")
    private val configuration = installation.resolve("config/environment")
    private val children = mutableListOf<Process>()
    private val queryChildren = mutableListOf<ProcessHandle>()

    init {
        Files.createDirectories(payload.parent)
        Files.copy(requiredExecutable("kast.queryExecutable"), payload)
        Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createDirectories(configuration.parent)
        Files.writeString(configuration, "KAST_INSTALL_IDEA_HOME=$idea\n")
        Files.createDirectories(installation.resolve("share/kast"))
        Files.writeString(installation.resolve("share/kast/one-shot-observation-v1"), "1\n")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"native-peer\"\n")
        val wrapper = installation.resolve("bin/kast-query-complete")
        Files.writeString(
            wrapper,
            "#!/bin/sh\nexport KAST_CONFIGURATION_FILE=${shellWord(configuration.toString())}\n" +
                "unset KAST_SESSION_ROOT\nexec ${shellWord(payload.toString())} \"\$@\"\n",
        )
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"))
        val payloads =
            listOf(payload, wrapper).map { file ->
                PayloadFile(installation.relativize(file).toString(), "sha256:${nativeDigest(file)}", 493)
            }
        Files.writeString(
            installation.resolve("installation.json"),
            Json { encodeDefaults = true }
                .encodeToString(
                    InstallationManifest(
                        semanticVersion = "0.0.0",
                        installationRoot = installation.toString(),
                        payloadIdentity = "sha256:${nativeDigest(payload)}",
                        controlSha256 = "sha256:${nativeDigest(payload)}",
                        codexHome = home.resolve(".codex").toString(),
                        configuration = configuration.toString(),
                        workspaceRegistry = installation.resolve("config/workspaces.json").toString(),
                        stateRoot = installation.resolve("state").toString(),
                        externalAnchors = emptyList(),
                        payloadFiles = payloads,
                    )
                ),
        )
        Files.writeString(evidence.resolve("installation-path.txt"), "$installation\n")
        Files.writeString(evidence.resolve("query-sha256.txt"), "${nativeDigest(payload)}\n")
        Files.copy(installation.resolve("installation.json"), evidence.resolve("installation.json"))
    }

    fun start(
        label: String,
        arguments: List<String>,
        captureOutput: Boolean = true,
        directory: Path = home,
    ): Process =
        ProcessBuilder(listOf(requiredExecutable("kast.publicLauncher").toString()) + arguments)
            .directory(directory.toFile())
            .redirectError(evidence.resolve("$label.stderr").toFile())
            .apply {
                environment().clear()
                environment()["HOME"] = home.toString()
                environment()["PATH"] = "/usr/bin:/bin"
                if (captureOutput) redirectOutput(evidence.resolve("$label.stdout").toFile())
            }
            .start()
            .also(children::add)

    fun awaitQueryChild(parent: Process): ProcessHandle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (parent.isAlive && System.nanoTime() < deadline) {
            val matches =
                parent.descendants().use { descendants ->
                    descendants.filter { it.info().command().orElse("") == payload.toString() }.toList()
                }
            if (matches.isNotEmpty()) {
                assertEquals(1, matches.size, "Expected one actual native payload")
                val child = matches.single()
                queryChildren.add(child)
                assertTrue(child.info().startInstant().isPresent, "Missing exact child start identity")
                Files.writeString(evidence.resolve("child-${child.pid()}.txt"), child.info().toString())
                return child
            }
            Thread.onSpinWait()
        }
        error("No selected native query child observed; evidence: $evidence")
    }

    fun assertExit(parent: Process, expected: Int, label: String) {
        assertTrue(parent.waitFor(20, TimeUnit.SECONDS), "Public process did not settle: $evidence")
        assertEquals(expected, parent.exitValue(), Files.readString(evidence.resolve("$label.stderr")))
    }

    fun assertReaped(child: ProcessHandle) {
        child.onExit().get(5, TimeUnit.SECONDS)
        assertFalse(child.isAlive, "Exact native query child survived public exit")
    }

    override fun close() {
        children.forEach(::stopNativeProcess)
        // Failed reaping assertions must not leave an orphan after its public parent has already exited.
        queryChildren.forEach { child ->
            if (child.isAlive) {
                child.destroyForcibly()
                child.onExit().get(5, TimeUnit.SECONDS)
            }
        }
        Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

internal fun requiredExecutable(property: String): Path {
    val path = Path.of(checkNotNull(System.getProperty(property)) { "Missing native test property $property" })
    check(Files.isRegularFile(path) && Files.isExecutable(path)) { "Unavailable native executable: $path" }
    val magic = Files.newInputStream(path).use { it.readNBytes(4) }.joinToString("") { "%02x".format(it) }
    check(magic in setOf("cffaedfe", "feedfacf", "7f454c46")) {
        "Expected Mach-O or ELF executable, not a JVM script: $path"
    }
    return path.toRealPath()
}

internal fun nativeDigest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        var size = input.read(buffer)
        while (size >= 0) {
            digest.update(buffer, 0, size)
            size = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun signalNativeProcess(parent: Process, signal: String) {
    val sender = ProcessBuilder("/bin/kill", "-s", signal, parent.pid().toString()).start()
    try {
        assertTrue(sender.waitFor(5, TimeUnit.SECONDS), "Signal sender did not settle")
        assertEquals(0, sender.exitValue())
    } finally {
        stopNativeProcess(sender)
    }
}

internal fun stopNativeProcess(process: Process) {
    if (process.isAlive) {
        val descendants = process.descendants().use { it.toList() }
        descendants.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Owned process did not exit" }
        descendants.forEach { it.onExit().get(5, TimeUnit.SECONDS) }
    }
}

/** Sampling establishes the actual worker syscall, not merely a live process or an elapsed sleep. */
internal fun awaitNativePipeStack(child: ProcessHandle, evidence: Path, operation: String) {
    check(Files.isExecutable(Path.of("/usr/bin/sample"))) { "Native pipe proof requires macOS sample" }
    val syscall = Regex("\\b(?:__)?${operation}(?:_nocancel)?(?:\\s|\\(|\\+)")
    repeat(5) { attempt ->
        assertTrue(child.isAlive, "Native child exited before its pipe precondition")
        val report = evidence.resolve("${operation}-${child.pid()}-$attempt.sample.txt")
        val sampler =
            ProcessBuilder("/usr/bin/sample", child.pid().toString(), "1", "1", "-file", report.toString())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve("${operation}-${child.pid()}-$attempt.sample.log").toFile())
                .start()
        try {
            assertTrue(sampler.waitFor(15, TimeUnit.SECONDS), "Native sampler did not settle")
            assertEquals(0, sampler.exitValue(), "Native stack collection failed: $evidence")
            val stacks = Files.readString(report).split(Regex("(?m)^\\s*\\d+ Thread_"))
            if (
                stacks.any { stack ->
                    // Native Image retains the last 15 characters in the macOS pthread name.
                    stack.lineSequence().first().endsWith(": st-public-query") &&
                        stack.lineSequence().any { line ->
                            line.contains("libsystem_kernel.dylib") && syscall.containsMatchIn(line)
                        }
                }
            )
                return
        } finally {
            stopNativeProcess(sampler)
        }
    }
    error("No native public worker blocked in $operation; evidence: $evidence")
}

private fun shellWord(value: String): String = "'" + value.replace("'", "'\\''") + "'"
