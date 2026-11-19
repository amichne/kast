package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.KastControlMain
import io.github.amichne.kast.kernel.Refinement
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.util.Base64
import kotlin.system.exitProcess

/**
 * A long-lived jq worker in its own JVM. It evaluates one framed request at a time and replies with a status and
 * output. Its owner kills it to stop an evaluation, because an embedded evaluation cannot be interrupted; standard
 * input closing ends it.
 */
internal object PublicJqWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isNotEmpty()) exitProcess(PublicJqWorkerExit.INVALID_REQUEST.code)
        val input = DataInputStream(BufferedInputStream(System.`in`))
        val output = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
        while (true) {
            val request = JqFrames.readRequest(input) ?: exitProcess(0)
            val reply =
                when (request) {
                    is Refinement.Rejected -> JqReply(request.failure.code, "")
                    is Refinement.Refined -> evaluate(request.value)
                }
            JqFrames.writeReply(output, reply)
            // A failed frame leaves the stream unaligned, and an exhausted heap leaves the JVM unreliable.
            if (request is Refinement.Rejected || reply.status == PublicJqWorkerExit.RESOURCE_LIMIT.code) exitProcess(0)
        }
    }

    private fun evaluate(request: JqRequest): JqReply =
        try {
            run(request)
        } catch (_: OutOfMemoryError) {
            JqReply(PublicJqWorkerExit.RESOURCE_LIMIT.code, "")
        } catch (_: StackOverflowError) {
            JqReply(PublicJqWorkerExit.RESOURCE_LIMIT.code, "")
        }

    private fun run(request: JqRequest): JqReply {
        if (request.maximumBytes !in 1..PublicJqLimits.MAXIMUM_BYTES)
            return JqReply(PublicJqWorkerExit.INVALID_REQUEST.code, "")
        val program =
            when (val decoded = decodeWorkerProgram(request.program)) {
                is Refinement.Rejected -> return JqReply(decoded.failure.code, "")
                is Refinement.Refined -> decoded.value
            }
        val compiled =
            when (val admitted = PublicJqProgram.compile(program)) {
                is Refinement.Rejected -> return JqReply(workerExit(admitted.failure).code, "")
                is Refinement.Refined -> admitted.value
            }
        if (request.stage == PublicJqStage.COMPILE) return JqReply(0, "")
        if (request.input.size > request.maximumBytes) return JqReply(PublicJqWorkerExit.BYTE_LIMIT.code, "")
        val text =
            try {
                request.input.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return JqReply(PublicJqWorkerExit.OUTPUT_INVALID.code, "")
            }
        return when (val result = compiled.evaluate(text, request.maximumBytes)) {
            is Refinement.Rejected -> JqReply(workerExit(result.failure).code, "")
            is Refinement.Refined -> JqReply(0, result.value.text)
        }
    }
}

/** One admitted request: the stage, its byte limit, the encoded program and the raw input document. */
internal class JqRequest(val stage: PublicJqStage, val maximumBytes: Int, val program: String, val input: ByteArray)

internal class JqReply(val status: Int, val output: String)

/**
 * The private worker protocol: length-prefixed fields over the worker's standard streams. Text is strict UTF-8, so the
 * runtime's text encoding never affects it.
 */
internal object JqFrames {
    private const val MAXIMUM_INPUT_BYTES = PublicJqLimits.MAXIMUM_BYTES
    private const val MAXIMUM_OUTPUT_BYTES = PublicJqLimits.MAXIMUM_BYTES

    fun writeRequest(output: DataOutputStream, request: JqRequest) {
        output.writeInt(request.stage.ordinal)
        output.writeInt(request.maximumBytes)
        field(output, request.program.encodeToByteArray())
        field(output, request.input)
        output.flush()
    }

    /** Null at a clean end of input; a malformed frame is a rejected request. */
    fun readRequest(input: DataInputStream): Refinement<JqRequest, PublicJqWorkerExit>? {
        val stage =
            try {
                input.readInt()
            } catch (_: EOFException) {
                return null
            }
        val rejected = Refinement.Rejected(PublicJqWorkerExit.INVALID_REQUEST)
        val selected =
            PublicJqStage.entries.getOrNull(stage)?.takeIf { it != PublicJqStage.PRESENTATION } ?: return rejected
        val maximumBytes = input.readInt()
        val program = field(input, MAX_PROGRAM_ARGUMENT_CHARACTERS) ?: return rejected
        val document = field(input, MAXIMUM_INPUT_BYTES) ?: return rejected
        return Refinement.Refined(JqRequest(selected, maximumBytes, program.decodeToString(), document))
    }

    fun writeReply(output: DataOutputStream, reply: JqReply) {
        output.writeInt(reply.status)
        field(output, reply.output.encodeToByteArray(throwOnInvalidSequence = true))
        output.flush()
    }

    /** Null when the reply's output exceeds [limit]; the stream is then unaligned and the worker must be stopped. */
    fun readReply(input: DataInputStream, limit: Int): Pair<Int, ByteArray>? {
        val status = input.readInt()
        val output = field(input, minOf(limit, MAXIMUM_OUTPUT_BYTES)) ?: return null
        return status to output
    }

    private fun field(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun field(input: DataInputStream, limit: Int): ByteArray? {
        val size = input.readInt()
        if (size !in 0..limit) return null
        return input.readNBytes(size).takeIf { it.size == size } ?: throw EOFException("truncated jq frame")
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
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    // Standard output carries worker frames; JVM warnings go to stderr.
                    "-Xlog:disable",
                    "-Xlog:all=warning:stderr",
                    "-Xmx512m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    KastControlMain::class.java.name,
                )
            return Refinement.Refined(PublicJqWorkerCommand(prefix + "--internal-entrypoint=jq"))
        }
    }
}

internal const val MAX_PROGRAM_BYTES = 32 * 1_024

internal const val MAX_PROGRAM_ARGUMENT_CHARACTERS = ((MAX_PROGRAM_BYTES + 2) / 3) * 4

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
