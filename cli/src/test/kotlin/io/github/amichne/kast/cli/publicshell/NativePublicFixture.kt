package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.InstalledSemanticCoordinator
import io.github.amichne.kast.appserver.NativeCoordinatorProbe
import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The staged Control product in a private installation: its Go executable, its JVM libraries and its catalog, run on
 * this test's Java runtime through a Host runtime root. No entry or invocation adapter is substituted.
 */
internal class NativePublicFixture(
    val evidence: Path,
    /**
     * Places a copy of the test's Java runtime in the workspace store, where an installed runtime lives, so a removal
     * selects its processes by their executable as it does on a real installation. Otherwise the runtime root links to
     * the test's own runtime, outside every Kast root.
     */
    private val ownedRuntime: Boolean = false,
) : AutoCloseable {
    // Unix-domain paths must fit the production 100-byte bound, independently of JUnit's temporary path.
    val home: Path = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kn -")
    val workspace: Path = Files.createDirectory(home.resolve("workspace with spaces"))
    val installation: Path = Files.createDirectories(home.resolve(".local/share/kast/installation"))
    val payload: Path = installation.resolve("share/kast/libexec/kast-management")
    /** The runtime every Control JVM process runs, as its process command reports it. */
    val javaExecutable: Path
        get() = javaHome.toRealPath().resolve("bin/java")

    private lateinit var javaHome: Path

    /** The public command, a copy of the payload, as the installer publishes it beneath HOME. */
    val command: Path = home.resolve(".local/bin/kast")
    private val configuration = installation.resolve("config/environment")
    private val children = mutableListOf<Process>()

    init {
        val product = controlProduct()
        Files.createDirectories(payload.parent)
        Files.copy(product.resolve(CONTROL_PAYLOAD), payload)
        Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createDirectories(command.parent)
        Files.copy(payload, command)
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwxr-xr-x"))
        val libraries = Files.createDirectories(installation.resolve("lib"))
        Files.list(product.resolve("lib")).use { jars ->
            jars.forEach { Files.copy(it, libraries.resolve(it.fileName)) }
        }
        Files.createDirectories(installation.resolve("share/kast"))
        Files.copy(product.resolve(CONTROL_CATALOG), installation.resolve(CONTROL_CATALOG))
        writeRuntimeReceipt()
        Files.createDirectories(configuration.parent)
        Files.writeString(configuration, "")
        Files.createDirectories(installation.resolve("share/kast"))
        Files.writeString(installation.resolve("share/kast/one-shot-observation-v1"), "1\n")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"native-peer\"\n")
        Files.createDirectories(installation.resolve("bin"))
        val query = installation.resolve("bin/kast-query")
        Files.writeString(
            query,
            "#!/bin/sh\nexec ${shellWord(payload.toString())} --internal-entrypoint=query \"\$@\"\n",
        )
        Files.setPosixFilePermissions(query, PosixFilePermissions.fromString("rwxr-xr-x"))
        val wrapper = installation.resolve("bin/kast-query-complete")
        Files.writeString(
            wrapper,
            "#!/bin/sh\nexport KAST_CONFIGURATION_FILE=${shellWord(configuration.toString())}\n" +
                "unset KAST_SESSION_ROOT\nexec ${shellWord(query.toString())} \"\$@\"\n",
        )
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"))
        val jars = Files.list(libraries).use { it.sorted().toList() }
        val payloads =
            listOf(payload, query, wrapper).map { file ->
                PayloadFile(installation.relativize(file).toString(), "sha256:${nativeDigest(file)}", 493)
            } +
                (jars + listOf(installation.resolve(CONTROL_CATALOG))).map { file ->
                    PayloadFile(installation.relativize(file).toString(), "sha256:${nativeDigest(file)}", 420)
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
        environment: Map<String, String> = mapOf("KAST_OUTPUT_TOON" to "0"),
    ): Process =
        ProcessBuilder(listOf(command.toString()) + arguments)
            .directory(directory.toFile())
            .redirectError(evidence.resolve("$label.stderr").toFile())
            .apply {
                environment().clear()
                environment()["HOME"] = home.toString()
                environment()["PATH"] = "/usr/bin:/bin"
                environment().putAll(environment)
                if (captureOutput) redirectOutput(evidence.resolve("$label.stdout").toFile())
            }
            .start()
            .also(children::add)

    fun startSemanticCoordinator() {
        val readiness = InstalledSemanticCoordinator.readinessPath(installation, home)
        Files.createDirectories(readiness.parent)
        val service = "sha256:" + digest(installation.toString())
        val kast = Files.writeString(installation.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
        assertTrue(kast.toFile().setExecutable(true))
        readiness.fileSystem.newWatchService().use { watch ->
            readiness.parent.register(
                watch,
                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
            )
            val process =
                ProcessBuilder(
                        payload.toString(),
                        "-Duser.home=$home",
                        "--internal-entrypoint=daemon",
                        "--semantic",
                    )
                    .directory(home.toFile())
                    .redirectError(evidence.resolve("semantic.stderr").toFile())
                    .redirectOutput(evidence.resolve("semantic.stdout").toFile())
                    .apply {
                        environment().clear()
                        environment()["HOME"] = home.toString()
                        environment()["PATH"] = "/usr/bin:/bin"
                        environment()["BROKER_SERVICE_IDENTITY"] = service
                        environment()["BROKER_READINESS_FILE"] = readiness.toString()
                    }
                    .start()
                    .also(children::add)
            awaitReadiness(watch, readiness, service, process)
        }
    }

    private fun awaitReadiness(
        watch: java.nio.file.WatchService,
        readiness: Path,
        service: String,
        process: Process,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!Files.exists(readiness) || !NativeCoordinatorProbe.ready(Files.readString(readiness), service)) {
            assertTrue(process.isAlive, Files.readString(evidence.resolve("semantic.stderr")).take(8192))
            val remaining = deadline - System.nanoTime()
            assertTrue(remaining > 0, "Native coordinator did not become ready")
            val key = checkNotNull(watch.poll(remaining, TimeUnit.NANOSECONDS))
            key.pollEvents()
            assertTrue(key.reset())
        }
    }

    /**
     * The installer records the Host runtime root in the management receipt; the Control executable selects this
     * platform's Java home inside it. Here that Java home is the test's own runtime.
     */
    private fun writeRuntimeReceipt() {
        val catalog = Json.parseToJsonElement(Files.readString(installation.resolve(CONTROL_CATALOG))).jsonObject
        val homes = catalog.getValue("control").jsonObject.getValue("javaHomes").jsonObject
        javaHome = runtimeRoot.resolve(homes.getValue(currentPlatform()).jsonPrimitive.content)
        Files.createDirectories(javaHome.parent)
        val testRuntime = Path.of(System.getProperty("java.home")).toRealPath()
        if (ownedRuntime) copyRuntime(testRuntime, javaHome) else Files.createSymbolicLink(javaHome, testRuntime)
        Files.writeString(
            installation.parent.resolve("management.json"),
            Json.encodeToString(RuntimeReceipt(RuntimeReference(runtimeRoot.toString()))),
        )
    }

    private val runtimeRoot: Path
        get() =
            if (ownedRuntime) home.resolve(".local/share/kast-workspaces/runtimes/fixture") else home.resolve("runtime")

    /** Copies every directory, file and link, so each copied executable is a separate file with its own path. */
    private fun copyRuntime(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path -> copyRuntimeEntry(path, destination.resolve(source.relativize(path).toString())) }
        }
    }

    private fun copyRuntimeEntry(path: Path, target: Path) {
        when {
            Files.isSymbolicLink(path) -> Files.createSymbolicLink(target, Files.readSymbolicLink(path))
            Files.isDirectory(path) -> Files.createDirectories(target)
            else -> {
                Files.copy(path, target)
                if (Files.isExecutable(path))
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    private fun digest(text: String): String =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    fun assertExit(parent: Process, expected: Int, label: String) {
        assertTrue(parent.waitFor(20, TimeUnit.SECONDS), "Public process did not settle: $evidence")
        assertEquals(expected, parent.exitValue(), Files.readString(evidence.resolve("$label.stderr")))
    }

    override fun close() {
        children.forEach(::stopNativeProcess)
        withdrawOwnedLaunchdJobs()
        Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /**
     * A public query with no running coordinator starts one through launchd. The job outlives this fixture's HOME
     * unless withdrawn, so remove exactly the semantic jobs whose definition lies beneath it.
     */
    private fun withdrawOwnedLaunchdJobs() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val domain = "gui/" + ProcessBuilder("/usr/bin/id", "-u").start().inputReader().readText().trim()
        val labels =
            launchctl("list").lineSequence().mapNotNull { line ->
                line.split('\t').lastOrNull()?.takeIf { it.startsWith("io.github.amichne.kast.semantic.") }
            }
        for (label in labels.toList()) {
            val definition = launchctl("print", "$domain/$label")
            val path = definition.lineSequence().firstOrNull { it.trim().startsWith("path = ") }?.trim()
            if (path != null && Path.of(path.removePrefix("path = ")).startsWith(home.toRealPath()))
                launchctl("bootout", "$domain/$label")
        }
    }

    private fun launchctl(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("/bin/launchctl") + arguments).redirectErrorStream(true).start()
        val output = process.inputReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "launchctl did not settle")
        return output
    }
}

/** The staged Control product this suite exercises, named by the `kast.controlProduct` test property. */
internal fun controlProduct(): Path {
    val path = Path.of(checkNotNull(System.getProperty("kast.controlProduct")) { "Missing kast.controlProduct" })
    val payload = path.resolve(CONTROL_PAYLOAD)
    check(Files.isExecutable(payload) && Files.isRegularFile(path.resolve(CONTROL_CATALOG))) {
        "Unavailable Control product: $path"
    }
    val magic = Files.newInputStream(payload).use { it.readNBytes(4) }.joinToString("") { "%02x".format(it) }
    check(magic in setOf("cffaedfe", "feedfacf", "7f454c46")) {
        "Expected a Mach-O or ELF Control executable: $payload"
    }
    return path.toRealPath()
}

private fun currentPlatform(): String {
    val os = if (System.getProperty("os.name").startsWith("Mac")) "macos" else "linux"
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    return "$os-$arch"
}

/** Only the runtime root the Control executable reads; every other receipt field stays the installer's. */
@kotlinx.serialization.Serializable private data class RuntimeReceipt(val product: RuntimeReference)

@kotlinx.serialization.Serializable private data class RuntimeReference(val runtimeRoot: String)

private const val CONTROL_PAYLOAD = "share/kast/libexec/kast-management"
private const val CONTROL_CATALOG = "share/kast/frontend.json"

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

private fun shellWord(value: String): String = "'" + value.replace("'", "'\\''") + "'"
