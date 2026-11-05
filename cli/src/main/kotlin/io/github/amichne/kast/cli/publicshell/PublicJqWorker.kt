package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.KastNativeMain
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.util.Base64
import kotlin.system.exitProcess

/** The same embedded evaluator is reachable in JVM tests and the installed native Control image. */
internal object PublicJqWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val exit =
            try {
                run(arguments.toList())
            } catch (_: OutOfMemoryError) {
                PublicJqWorkerExit.RESOURCE_LIMIT.code
            } catch (_: StackOverflowError) {
                PublicJqWorkerExit.RESOURCE_LIMIT.code
            }
        exitProcess(exit)
    }

    private fun run(arguments: List<String>): Int {
        if (arguments.size != WORKER_ARGUMENT_COUNT) return PublicJqWorkerExit.INVALID_REQUEST.code
        val stage = arguments[0]
        val bytes = arguments[1].toIntOrNull() ?: return PublicJqWorkerExit.INVALID_REQUEST.code
        if (stage !in listOf("COMPILE", "FILTER") || bytes !in 1..PublicJqLimits.MAXIMUM_BYTES)
            return PublicJqWorkerExit.INVALID_REQUEST.code
        val program =
            when (val decoded = decodeWorkerProgram(arguments[2])) {
                is Refinement.Rejected -> return decoded.failure.code
                is Refinement.Refined -> decoded.value
            }
        val compiled =
            when (val admitted = PublicJqProgram.compile(program)) {
                is Refinement.Rejected -> return workerExit(admitted.failure).code
                is Refinement.Refined -> admitted.value
            }
        if (stage == "COMPILE") return 0
        val text =
            when (val input = readWorkerInput(bytes)) {
                is Refinement.Rejected -> return input.failure.code
                is Refinement.Refined -> input.value
            }
        return when (val result = compiled.evaluate(text, bytes)) {
            is Refinement.Rejected -> workerExit(result.failure).code
            is Refinement.Refined -> {
                // The private protocol is UTF-8 even when the cleared environment selects ASCII stdout.
                System.out.write(result.value.text.encodeToByteArray(throwOnInvalidSequence = true))
                System.out.flush()
                0
            }
        }
    }
}

@Suppress("MagicNumber") // Fixed private protocol statuses, distinct from published shell exit statuses.
/** A closed private process protocol; diagnostics never depend on parsing library exception text. */
internal enum class PublicJqWorkerExit(val code: Int, val failure: PublicJqFailure) {
    INVALID_REQUEST(20, PublicJqFailure.ENGINE_UNAVAILABLE),
    PROGRAM_INVALID(21, PublicJqFailure.PROGRAM_INVALID),
    EVALUATION_FAILED(22, PublicJqFailure.EVALUATION_FAILED),
    OUTPUT_CARDINALITY(23, PublicJqFailure.OUTPUT_CARDINALITY),
    NUMERIC_VALUE_LOSS(24, PublicJqFailure.NUMERIC_VALUE_LOSS),
    BYTE_LIMIT(25, PublicJqFailure.BYTE_LIMIT),
    OUTPUT_INVALID(26, PublicJqFailure.OUTPUT_INVALID),
    PROGRAM_UNSUPPORTED(27, PublicJqFailure.PROGRAM_UNSUPPORTED),
    RESOURCE_LIMIT(28, PublicJqFailure.RESOURCE_LIMIT),
    JSON_LIMIT(29, PublicJqFailure.JSON_LIMIT),
    NUMERIC_LITERAL_LIMIT(30, PublicJqFailure.NUMERIC_LITERAL_LIMIT),
}

private fun workerExit(failure: PublicJqFailure): PublicJqWorkerExit =
    PublicJqWorkerExit.entries.single { it.failure == failure }

/** Admitted self-runtime command; filter programs cannot choose an executable or inherit a host environment. */
internal class PublicJqWorkerCommand private constructor(val arguments: List<String>) {
    companion object {
        fun current(): Refinement<PublicJqWorkerCommand, PublicJqFailure> {
            val prefix =
                if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
                    val executable =
                        ProcessHandle.current().info().command().orElse(null)
                            ?: return Refinement.Rejected(PublicJqFailure.ENGINE_UNAVAILABLE)
                    listOf(executable, "-Xmx512m")
                } else {
                    listOf(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx512m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        KastNativeMain::class.java.name,
                    )
                }
            return Refinement.Refined(PublicJqWorkerCommand(prefix + "--internal-entrypoint=jq"))
        }
    }
}

internal const val MAX_PROGRAM_BYTES = 32 * 1_024

private const val WORKER_ARGUMENT_COUNT = 3
private const val MAX_PROGRAM_ARGUMENT_CHARACTERS = ((MAX_PROGRAM_BYTES + 2) / 3) * 4

/** ASCII transport avoids locale-dependent argv decoding; decoded source is still bounded strict UTF-8. */
private fun decodeWorkerProgram(argument: String): Refinement<String, PublicJqWorkerExit> {
    if (argument.length > MAX_PROGRAM_ARGUMENT_CHARACTERS)
        return Refinement.Rejected(PublicJqWorkerExit.INVALID_REQUEST)
    val bytes =
        try {
            Base64.getDecoder().decode(argument)
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(PublicJqWorkerExit.INVALID_REQUEST)
        }
    if (bytes.size > MAX_PROGRAM_BYTES) return Refinement.Rejected(PublicJqWorkerExit.INVALID_REQUEST)
    val program =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return Refinement.Rejected(PublicJqWorkerExit.INVALID_REQUEST)
        }
    if (program.isBlank() || '\u0000' in program) return Refinement.Rejected(PublicJqWorkerExit.INVALID_REQUEST)
    return Refinement.Refined(program)
}

private fun readWorkerInput(maxBytes: Int): Refinement<String, PublicJqWorkerExit> {
    val input = System.`in`.readNBytes(maxBytes + 1)
    if (input.size > maxBytes) return Refinement.Rejected(PublicJqWorkerExit.BYTE_LIMIT)
    return try {
        Refinement.Refined(input.decodeToString(throwOnInvalidSequence = true))
    } catch (_: java.nio.charset.CharacterCodingException) {
        Refinement.Rejected(PublicJqWorkerExit.OUTPUT_INVALID)
    }
}
