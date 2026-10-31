package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.io.StringReader
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.Serializable

@Serializable
internal data class LegacyHostProfile(val ideaHome: String, val profile: String, val process: ManagedIdeProcess)

internal sealed interface LegacyProfileRead {
    data class Captured(val profile: LegacyHostProfile) : LegacyProfileRead

    data object Unavailable : LegacyProfileRead
}

internal enum class LegacyQuitOutcome {
    REQUESTED,
    REJECTED,
}

private sealed interface LegacyHelperRead {
    data class Read(val bytes: ByteArray) : LegacyHelperRead

    data object Unavailable : LegacyHelperRead
}

/** JCMD belongs only to explicit legacy installer inspection, never dry-run or semantic-query admission. */
internal fun inspectLegacyHostProfile(
    launch: SelectedIdeLaunch.Resolved,
    expected: ManagedIdeProcess,
): LegacyProfileRead {
    val process = ProcessHandle.of(expected.pid)
    if (process.isEmpty || !matchesLegacyProcess(process.get(), expected)) return LegacyProfileRead.Unavailable
    val bytes =
        when (
            val captured =
                boundedLegacyHelper(
                    listOf(
                        launch.bundledJavaHome.resolve("bin/jcmd").toString(),
                        expected.pid.toString(),
                        "VM.system_properties",
                    )
                )
        ) {
            is LegacyHelperRead.Read -> captured.bytes
            LegacyHelperRead.Unavailable -> return LegacyProfileRead.Unavailable
        }
    if (!matchesLegacyProcess(process.get(), expected)) return LegacyProfileRead.Unavailable
    return decodeLegacyProfile(launch, expected, process.get(), bytes)
}

private fun decodeLegacyProfile(
    launch: SelectedIdeLaunch.Resolved,
    expected: ManagedIdeProcess,
    process: ProcessHandle,
    bytes: ByteArray,
): LegacyProfileRead {
    return try {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        if (!text.startsWith("${expected.pid}:\n")) return LegacyProfileRead.Unavailable
        val properties = Properties()
        properties.load(StringReader(text.substringAfter('\n')))
        when (val selected = selectLegacyProfile(Path.of(launch.home), captureLegacyProfileProperties(properties))) {
            is Refinement.Rejected -> {
                reportLegacyProfileSelection(LegacyProfileObservation.Rejected(selected.failure))
                LegacyProfileRead.Unavailable
            }
            is Refinement.Refined -> {
                val paths = selected.value
                val profile =
                    LegacyHostProfile(
                        paths.ideaHome.toRealPath().toString(),
                        paths.profile.toRealPath().toString(),
                        expected,
                    )
                if (!matchesLegacyProcess(process, expected)) return LegacyProfileRead.Unavailable
                reportLegacyProfileSelection(LegacyProfileObservation.Admitted(paths.kind))
                LegacyProfileRead.Captured(profile)
            }
        }
    } catch (_: IOException) {
        LegacyProfileRead.Unavailable
    } catch (_: IllegalArgumentException) {
        LegacyProfileRead.Unavailable
    }
}

private fun captureLegacyProfileProperties(properties: Properties): LegacyProfileProperties =
    LegacyProfileProperties(
        ideaHome = properties.getProperty("idea.home.path"),
        configPath = properties.getProperty("idea.config.path"),
        pluginsPath = properties.getProperty("idea.plugins.path"),
        selector = properties.getProperty("idea.paths.selector"),
        vendor = properties.getProperty("idea.vendor.name"),
        userHome = properties.getProperty("user.home"),
        operatingSystem = properties.getProperty("os.name"),
        customizer = properties.getProperty("idea.paths.customizer"),
    )

/** NSRunningApplication sends the normal application quit request to the admitted application instance. */
internal fun stopLegacyHostNormally(
    launch: SelectedIdeLaunch.Resolved,
    expected: ManagedIdeProcess,
): LegacyQuitOutcome {
    val process = ProcessHandle.of(expected.pid)
    if (process.isEmpty || !matchesLegacyProcess(process.get(), expected)) return LegacyQuitOutcome.REJECTED
    val script =
        """ObjC.import('AppKit'); function run(argv) { var a = $.NSRunningApplication.runningApplicationWithProcessIdentifier(Number(argv[1])); if (!a || !a.bundleURL || ObjC.unwrap(a.bundleURL.path) !== argv[0]) return 'rejected'; return a.terminate() ? 'requested' : 'rejected'; }"""
    return when (
        val response =
            boundedLegacyHelper(
                listOf("/usr/bin/osascript", "-l", "JavaScript", "-e", script, launch.bundle, expected.pid.toString())
            )
    ) {
        is LegacyHelperRead.Read ->
            if (response.bytes.decodeToString().trim() == "requested") LegacyQuitOutcome.REQUESTED
            else LegacyQuitOutcome.REJECTED
        LegacyHelperRead.Unavailable -> LegacyQuitOutcome.REJECTED
    }
}

private fun matchesLegacyProcess(process: ProcessHandle, expected: ManagedIdeProcess): Boolean {
    val info = process.info()
    return process.isAlive &&
        info.command().orElse("") == expected.executable &&
        info.startInstant().map { it.toEpochMilli() }.orElse(-1) == expected.processStartEpochMillis
}

private fun boundedLegacyHelper(arguments: List<String>): LegacyHelperRead {
    val child =
        try {
            val builder = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD)
            for (name in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS")) builder
                .environment()
                .remove(name)
            builder.start()
        } catch (_: IOException) {
            return LegacyHelperRead.Unavailable
        }
    return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val capture = executor.submit<ByteArray> { child.inputStream.use { it.readNBytes(MAXIMUM_PROPERTY_BYTES + 1) } }
        try {
            readLegacyHelper(child, capture)
        } catch (_: TimeoutException) {
            child.destroyForcibly()
            LegacyHelperRead.Unavailable
        } catch (_: java.util.concurrent.ExecutionException) {
            LegacyHelperRead.Unavailable
        } catch (_: InterruptedException) {
            child.destroyForcibly()
            Thread.currentThread().interrupt()
            LegacyHelperRead.Unavailable
        } finally {
            capture.cancel(true)
        }
    }
}

private fun readLegacyHelper(child: Process, capture: java.util.concurrent.Future<ByteArray>): LegacyHelperRead {
    if (!child.waitFor(HELPER_SECONDS, TimeUnit.SECONDS)) {
        child.destroyForcibly()
        return LegacyHelperRead.Unavailable
    }
    val bytes = capture.get(HELPER_SECONDS, TimeUnit.SECONDS)
    return if (child.exitValue() == 0 && bytes.size <= MAXIMUM_PROPERTY_BYTES) LegacyHelperRead.Read(bytes)
    else LegacyHelperRead.Unavailable
}

private const val MAXIMUM_PROPERTY_BYTES = 1_048_576
private const val HELPER_SECONDS = 10L
