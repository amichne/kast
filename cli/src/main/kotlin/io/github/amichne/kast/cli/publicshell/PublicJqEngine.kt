package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.distribution.output.JsonProjectionDocument
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A resolved executable, admitted stable version and successfully compiled program for this invocation. */
internal class PublicJqEngine
private constructor(
    private val executable: Path,
    private val version: PublicJqVersion,
    private val options: PublicJqOptions,
    private val executor: PublicJqExecutor,
) {
    fun project(prepared: PublicPreparedOutput): Refinement<PublicPreparedOutput, PublicJqRejection> {
        if (prepared.exit != PublicShellExit.COMPLETE || prepared.content != PublicOutputContent.DOCUMENT)
            return Refinement.Refined(prepared)
        val recovery = prepared.recovery
        val request =
            PublicJqProcessRequest(
                listOf(executable.toString(), "-c", "--", options.program),
                prepared.text,
                PublicJqStage.FILTER,
                options.limits,
            )
        val captured =
            when (val result = executor.execute(request)) {
                is Refinement.Rejected ->
                    return Refinement.Rejected(result.failure.copy(engineVersion = version.text, recovery = recovery))
                is Refinement.Refined -> result.value
            }
        if (captured.childExit != 0) return failure(PublicJqFailure.EVALUATION_FAILED, prepared, captured.childExit)
        val documents = captured.stdout.lineSequence().filter(String::isNotBlank).take(2).toList()
        if (documents.size != 1) return failure(PublicJqFailure.OUTPUT_CARDINALITY, prepared)
        // jq -c writes one JSON text per line. Strings contain escaped newlines; do not parse TOON or flatten arrays.
        if (JsonProjectionDocument.parse(documents.single()) is Refinement.Rejected)
            return failure(PublicJqFailure.OUTPUT_INVALID, prepared)
        return Refinement.Refined(
            prepared.copy(text = documents.single(), content = PublicOutputContent.Projection(options.limits, recovery))
        )
    }

    private fun failure(
        cause: PublicJqFailure,
        original: PublicPreparedOutput,
        childExit: Int? = null,
    ): Refinement.Rejected<PublicJqRejection> =
        Refinement.Rejected(
            jqRejection(cause, PublicJqStage.FILTER, options.limits, childExit, version.text)
                .copy(recovery = original.recovery)
        )

    companion object {
        fun prepare(
            options: PublicJqOptions,
            directory: Path,
            environment: Map<String, String>,
            executor: PublicJqExecutor = PublicJqProcess(directory, environment),
        ): Refinement<PublicJqEngine, PublicJqRejection> {
            val executable =
                selectExecutable(options.executable, directory, environment)
                    ?: return Refinement.Rejected(
                        jqRejection(PublicJqFailure.ENGINE_UNAVAILABLE, PublicJqStage.EXECUTABLE, options.limits)
                    )
            val version =
                when (val admitted = readJqVersion(executable, options, executor)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            when (val compiled = compileJqProgram(executable, version, options, executor)) {
                is Refinement.Rejected -> return compiled
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(PublicJqEngine(executable, version, options, executor))
        }
    }
}

internal class PublicJqVersion private constructor(val text: String) {
    companion object {
        fun admit(version: String): Refinement<PublicJqVersion, PublicJqFailure> {
            val rejected = Refinement.Rejected(PublicJqFailure.ENGINE_UNSUPPORTED)
            val match = Regex("jq-([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?").matchEntire(version) ?: return rejected
            val major = match.groupValues[1].toIntOrNull() ?: return rejected
            val minor = match.groupValues[2].toIntOrNull() ?: return rejected
            val patch = if (match.groupValues[3].isEmpty()) 0 else match.groupValues[3].toIntOrNull() ?: return rejected
            if (major != JQ_MAJOR || minor < JQ_MINIMUM_MINOR) return rejected
            if (minor == JQ_MINIMUM_MINOR && patch < JQ_MINIMUM_PATCH) return rejected
            return Refinement.Refined(PublicJqVersion(version))
        }
    }
}

private fun selectExecutable(explicit: Path?, directory: Path, environment: Map<String, String>): Path? =
    try {
        if (explicit != null) admitExecutable(explicit)
        else {
            val root = directory.toRealPath()
            environment["PATH"]
                .orEmpty()
                .split(java.io.File.pathSeparatorChar)
                .asSequence()
                .mapNotNull { automaticJqCandidate(it, root) }
                .firstOrNull()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun automaticJqCandidate(raw: String, directory: Path): Path? =
    try {
        val entry = Path.of(raw)
        if (!entry.isAbsolute) null else admitExecutable(entry.resolve("jq"))?.takeUnless { it.startsWith(directory) }
    } catch (_: InvalidPathException) {
        null
    } catch (_: IOException) {
        null
    }

private fun admitExecutable(path: Path): Path? =
    path.toRealPath().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }

private fun readJqVersion(
    executable: Path,
    options: PublicJqOptions,
    executor: PublicJqExecutor,
): Refinement<PublicJqVersion, PublicJqRejection> {
    val versionRequest =
        PublicJqProcessRequest(
            listOf(executable.toString(), "--version"),
            "",
            PublicJqStage.VERSION,
            options.limits.versionProbe(),
        )
    val observed =
        when (val result = executor.execute(versionRequest)) {
            is Refinement.Rejected -> return result
            is Refinement.Refined -> result.value
        }
    if (observed.childExit != 0)
        return Refinement.Rejected(
            jqRejection(
                PublicJqFailure.ENGINE_UNSUPPORTED,
                PublicJqStage.VERSION,
                options.limits,
                observed.childExit,
            )
        )
    return when (val admitted = PublicJqVersion.admit(observed.stdout.trim())) {
        is Refinement.Refined -> Refinement.Refined(admitted.value)
        is Refinement.Rejected ->
            return Refinement.Rejected(jqRejection(admitted.failure, PublicJqStage.VERSION, options.limits))
    }
}

private fun compileJqProgram(
    executable: Path,
    version: PublicJqVersion,
    options: PublicJqOptions,
    executor: PublicJqExecutor,
): Refinement<Unit, PublicJqRejection> {
    val compilation =
        PublicJqProcessRequest(
            listOf(executable.toString(), "-c", "--", options.program),
            "",
            PublicJqStage.COMPILE,
            options.limits,
        )
    when (val result = executor.execute(compilation)) {
        is Refinement.Rejected -> return result
        is Refinement.Refined ->
            if (result.value.childExit != 0 || result.value.stdout.isNotEmpty())
                return Refinement.Rejected(
                    jqRejection(
                        PublicJqFailure.PROGRAM_INVALID,
                        PublicJqStage.COMPILE,
                        options.limits,
                        result.value.childExit,
                        version.text,
                    )
                )
    }

    return Refinement.Refined(Unit)
}

private const val JQ_MAJOR = 1
private const val JQ_MINIMUM_MINOR = 8
private const val JQ_MINIMUM_PATCH = 2
